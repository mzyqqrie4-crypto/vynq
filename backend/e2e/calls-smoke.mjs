// Live checks for Phase 4: calls between contacts and the livekit-token function.
// Prints no token or secret: only what the token grants (room, identity, expiry).
// Test users are deleted at the end.
//
// Env: SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY (one command only).
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const { SUPABASE_URL: url, SUPABASE_ANON_KEY: anon, SUPABASE_SERVICE_ROLE_KEY: service } = process.env;
if (!url || !anon || !service) throw new Error('missing env');

async function http(method, path, { body, token, key = anon } = {}) {
  const res = await fetch(url + path, {
    method,
    headers: { apikey: key, ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* */ }
  return { status: res.status, json };
}
const admin = (method, path, body) => http(method, path, { body, key: service, token: service });
const rpc = (s, fn, body) => http('POST', `/rest/v1/rpc/${fn}`, { token: s.access_token, body });
const results = [];
async function step(name, fn) {
  try { await fn(); results.push(['PASS', name]); } catch (e) { results.push(['FAIL', `${name}: ${e.message}`]); }
}

const tag = randomUUID().slice(0, 6);
async function user(label) {
  const email = `vynq-e2e-${label}-${tag}@example.com`;
  assert.equal((await admin('POST', '/auth/v1/admin/users', { email, email_confirm: true })).status, 200);
  const link = await admin('POST', '/auth/v1/admin/generate_link', { type: 'magiclink', email });
  const code = link.json?.email_otp ?? link.json?.properties?.email_otp;
  const s = (await http('POST', '/auth/v1/verify', { body: { type: 'email', email, token: code } })).json;
  s.name = `e2e${label}${tag}`; s.device = randomUUID();
  await rpc(s, 'claim_username', { p_username: s.name });
  await rpc(s, 'register_device', { p_device_id: s.device, p_platform: 'android', p_name: 'E2E' });
  return s;
}
const claims = (jwt) => JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString());
let a, b, c, callId;

await step('users and contacts', async () => {
  a = await user('a'); b = await user('b'); c = await user('c');
  await rpc(a, 'request_contact', { p_username: b.name });
  assert.equal((await rpc(b, 'accept_contact', { p_user_id: a.user.id })).status, 204);
});

await step('a call can only be created to a contact', async () => {
  assert.equal((await rpc(a, 'create_call', { p_callee: c.user.id })).status, 403);
  const r = await rpc(a, 'create_call', { p_callee: b.user.id, p_video: true });
  assert.equal(r.status, 200, JSON.stringify(r.json));
  callId = r.json;
});

await step('both participants get a token for exactly this room and device', async () => {
  for (const s of [a, b]) {
    const r = await http('POST', '/functions/v1/livekit-token', { token: s.access_token, body: { call_id: callId, device_id: s.device } });
    assert.equal(r.status, 200, `${r.status} ${JSON.stringify(r.json)}`);
    assert.match(r.json.url, /^wss:\/\//);
    const t = claims(r.json.token);
    assert.equal(t.video.room, callId);
    assert.equal(t.video.roomJoin, true);
    assert.equal(t.sub, `${s.user.id}:${s.device}`);
    assert.ok(t.exp - t.nbf <= 600 || t.exp - Math.floor(Date.now() / 1000) <= 610, 'token lives at most 10 minutes');
  }
  console.log(`  LiveKit host: ${new URL((await http('POST', '/functions/v1/livekit-token', { token: a.access_token, body: { call_id: callId, device_id: a.device } })).json.url).host}`);
});

await step('strangers, other devices and anonymous callers get nothing', async () => {
  assert.equal((await http('POST', '/functions/v1/livekit-token', { token: c.access_token, body: { call_id: callId, device_id: c.device } })).status, 403);
  assert.equal((await http('POST', '/functions/v1/livekit-token', { token: a.access_token, body: { call_id: callId, device_id: b.device } })).status, 403);
  assert.equal((await http('POST', '/functions/v1/livekit-token', { body: { call_id: callId, device_id: a.device } })).status, 401);
});

await step('an ended call grants no more tokens', async () => {
  assert.equal((await rpc(b, 'update_call', { p_call: callId, p_state: 'ended' })).status, 204);
  assert.equal((await http('POST', '/functions/v1/livekit-token', { token: a.access_token, body: { call_id: callId, device_id: a.device } })).status, 403);
});

for (const s of [a, b, c]) if (s?.user?.id) await admin('DELETE', `/auth/v1/admin/users/${s.user.id}`);
for (const [r, n] of results) console.log(`${r}  ${n}`);
const failed = results.filter(([r]) => r === 'FAIL').length;
console.log(`\n${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
