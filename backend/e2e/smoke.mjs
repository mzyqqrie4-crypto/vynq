// End-to-end smoke test against a real Supabase project. Sends no email:
// the admin API mints the 6-digit code, then everything else goes through the
// same public endpoints the app uses. Test users delete themselves at the end.
//
// Env: SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY (admin key:
// keep it in the environment of one command only; never write it to disk).
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const { SUPABASE_URL: url, SUPABASE_ANON_KEY: anon, SUPABASE_SERVICE_ROLE_KEY: service } = process.env;
if (!url || !anon || !service) throw new Error('missing env');

async function http(method, path, { body, token, key = anon } = {}) {
  const res = await fetch(url + path, {
    method,
    headers: {
      apikey: key,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }
  return { status: res.status, json };
}

// The admin API needs the service-role JWT in Authorization as well as apikey.
const admin = (method, path, body) => http(method, path, { body, key: service, token: service });
const results = [];
async function step(name, fn) {
  try { await fn(); results.push(['PASS', name]); }
  catch (e) { results.push(['FAIL', `${name}: ${e.message}`]); }
}

// Signs in like the app: admin mints the code that would have been emailed.
async function signIn(email) {
  const created = await admin('POST', '/auth/v1/admin/users', { email, email_confirm: true });
  assert.equal(created.status, 200, `create user ${created.status}`);
  const link = await admin('POST', '/auth/v1/admin/generate_link', { type: 'magiclink', email });
  const code = link.json?.email_otp ?? link.json?.properties?.email_otp;
  assert.match(String(code), /^\d{6}$/, 'admin returned a 6-digit code');
  const verified = await http('POST', '/auth/v1/verify', { body: { type: 'email', email, token: code } });
  assert.equal(verified.status, 200, `verify ${verified.status}`);
  return verified.json;
}

const tag = randomUUID().slice(0, 8);
const emailA = `vynq-e2e-a-${tag}@example.com`;
const emailB = `vynq-e2e-b-${tag}@example.com`;
let a, b;

await step('code length on the server is 6 and sign-in works', async () => {
  a = await signIn(emailA);
  b = await signIn(emailB);
  assert.ok(a.access_token && a.refresh_token && a.user.id);
});

await step('a wrong code is rejected with otp_expired', async () => {
  const r = await http('POST', '/auth/v1/verify', { body: { type: 'email', email: emailA, token: '000000' } });
  assert.equal(r.status, 403);
  assert.equal(r.json.error_code, 'otp_expired');
});

await step('a profile row exists and only the owner sees it', async () => {
  const mine = await http('GET', '/rest/v1/profiles?select=user_id,username', { token: a.access_token });
  assert.deepEqual(mine.json, [{ user_id: a.user.id, username: null }]);
  const anonRead = await http('GET', '/rest/v1/profiles?select=user_id');
  assert.ok(anonRead.status === 401 || (anonRead.status === 200 && anonRead.json.length === 0), `anon got ${anonRead.status}`);
});

const name = `e2e_${tag}`;
await step('claim username, and the error bodies match what the app maps', async () => {
  const ok = await http('POST', '/rest/v1/rpc/claim_username', { token: a.access_token, body: { p_username: name.toUpperCase() } });
  assert.equal(ok.status, 200);
  assert.equal(ok.json, name);
  const taken = await http('POST', '/rest/v1/rpc/claim_username', { token: b.access_token, body: { p_username: name.replace('_', '.') } });
  assert.equal(taken.status, 409);
  assert.equal(taken.json.message, 'username_taken');
  const reserved = await http('POST', '/rest/v1/rpc/claim_username', { token: b.access_token, body: { p_username: 'adm1n' } });
  assert.equal(reserved.status, 409);
  assert.equal(reserved.json.message, 'username_reserved');
  const anonClaim = await http('POST', '/rest/v1/rpc/claim_username', { body: { p_username: 'ghost_user' } });
  assert.ok(anonClaim.status === 401 || anonClaim.status === 403, `anon claim ${anonClaim.status}`);
});

await step('username column cannot be written directly', async () => {
  const r = await http('PATCH', `/rest/v1/profiles?user_id=eq.${a.user.id}`, { token: a.access_token, body: { username: 'sneaky' } });
  assert.ok(r.status === 401 || r.status === 403, `patch ${r.status}`);
});

await step('lookup finds exact names and never returns email', async () => {
  const r = await http('POST', '/rest/v1/rpc/lookup_username', { token: b.access_token, body: { p_username: name } });
  assert.equal(r.status, 200);
  assert.equal(r.json.length, 1);
  assert.deepEqual(Object.keys(r.json[0]).sort(), ['display_name', 'user_id', 'username']);
});

await step('device registration, isolation and takeover protection', async () => {
  const id = randomUUID();
  const reg = await http('POST', '/rest/v1/rpc/register_device', { token: a.access_token, body: { p_device_id: id, p_platform: 'android', p_name: 'E2E phone' } });
  assert.ok(reg.status === 200 || reg.status === 204, `register ${reg.status}`);
  const seenByB = await http('GET', '/rest/v1/devices?select=device_id', { token: b.access_token });
  assert.deepEqual(seenByB.json, []);
  const takeover = await http('POST', '/rest/v1/rpc/register_device', { token: b.access_token, body: { p_device_id: id, p_platform: 'android', p_name: 'x' } });
  assert.equal(takeover.status, 403);
  assert.equal(takeover.json.message, 'device_unavailable');
});

await step('refresh token rotates, and sign-out revokes it', async () => {
  const r1 = await http('POST', '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: b.refresh_token } });
  assert.equal(r1.status, 200);
  const out = await http('POST', '/auth/v1/logout?scope=local', { token: r1.json.access_token });
  assert.equal(out.status, 204);
  const reuse = await http('POST', '/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: r1.json.refresh_token } });
  assert.ok(reuse.status >= 400, `revoked refresh token still works (${reuse.status})`);
  b = await signIn(emailB).catch(async () => {
    // user already exists: just mint a fresh code
    const link = await admin('POST', '/auth/v1/admin/generate_link', { type: 'magiclink', email: emailB });
    const code = link.json?.email_otp ?? link.json?.properties?.email_otp;
    return (await http('POST', '/auth/v1/verify', { body: { type: 'email', email: emailB, token: code } })).json;
  });
});

await step('delete-account rejects anonymous calls', async () => {
  const r = await http('POST', '/functions/v1/delete-account', { body: {} });
  assert.equal(r.status, 401);
});

await step('delete-account removes the user and cascades', async () => {
  for (const s of [a, b]) {
    const r = await http('POST', '/functions/v1/delete-account', { token: s.access_token, body: {} });
    assert.equal(r.status, 200, `delete ${r.status} ${JSON.stringify(r.json)}`);
  }
  const gone = await admin('GET', `/auth/v1/admin/users/${a.user.id}`);
  assert.equal(gone.status, 404);
  const rows = await http('GET', `/rest/v1/profiles?select=user_id&user_id=eq.${a.user.id}`, { key: service, token: service });
  assert.deepEqual(rows.json, []);
});

// Safety net: never leave test users behind, even after a failure.
for (const s of [a, b]) if (s?.user?.id) await admin('DELETE', `/auth/v1/admin/users/${s.user.id}`);

for (const [r, n] of results) console.log(`${r}  ${n}`);
const failed = results.filter(([r]) => r === 'FAIL').length;
console.log(`\n${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
