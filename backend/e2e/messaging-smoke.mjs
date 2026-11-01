// Live checks for Phase 3 against the real project: contacts, key bundles,
// the ciphertext mailbox, attachment storage policies, Realtime notifications
// and account deletion purging attachments. The "ciphertext" here is random
// bytes: this checks the server's rules, not the encryption (that runs on the
// phones and in the Android tests). Test users are deleted at the end.
//
// Env: SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY (one command only).
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';

const { SUPABASE_URL: url, SUPABASE_ANON_KEY: anon, SUPABASE_SERVICE_ROLE_KEY: service } = process.env;
if (!url || !anon || !service) throw new Error('missing env');

async function http(method, path, { body, token, key = anon, raw, contentType = 'application/json' } = {}) {
  const res = await fetch(url + path, {
    method,
    headers: { apikey: key, ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': contentType },
    body: raw ?? (body === undefined ? undefined : JSON.stringify(body)),
  });
  const buf = Buffer.from(await res.arrayBuffer());
  let json = null;
  try { json = buf.length ? JSON.parse(buf.toString()) : null; } catch { /* binary */ }
  return { status: res.status, json, buf };
}
const admin = (method, path, body) => http(method, path, { body, key: service, token: service });
const rpc = (s, fn, body) => http('POST', `/rest/v1/rpc/${fn}`, { token: s.access_token, body });

const results = [];
async function step(name, fn) {
  try { await fn(); results.push(['PASS', name]); } catch (e) { results.push(['FAIL', `${name}: ${e.message}`]); }
}

async function user(label) {
  const email = `vynq-e2e-${label}-${tag}@example.com`;
  const created = await admin('POST', '/auth/v1/admin/users', { email, email_confirm: true });
  assert.equal(created.status, 200, `create ${created.status}`);
  const link = await admin('POST', '/auth/v1/admin/generate_link', { type: 'magiclink', email });
  const code = link.json?.email_otp ?? link.json?.properties?.email_otp;
  const s = (await http('POST', '/auth/v1/verify', { body: { type: 'email', email, token: code } })).json;
  s.name = `e2e${label}${tag}`;
  s.device = randomUUID();
  assert.equal((await rpc(s, 'claim_username', { p_username: s.name })).status, 200);
  assert.equal((await rpc(s, 'register_device', { p_device_id: s.device, p_platform: 'android', p_name: 'E2E' })).status, 204);
  const b64 = (n) => randomBytes(n).toString('base64');
  const r = await rpc(s, 'publish_keys', {
    p_device: s.device, p_identity_key: b64(33), p_registration_id: 77,
    p_signed: { id: 1, key: b64(33), sig: b64(64) }, p_kyber: { id: 1, key: b64(1569), sig: b64(64) },
    p_one_time: [1, 2, 3].map((id) => ({ id, key: b64(33) })),
  });
  assert.equal(r.status, 204, `publish_keys ${r.status} ${JSON.stringify(r.json)}`);
  return s;
}

const tag = randomUUID().slice(0, 6);
let a, b, c;
const blobPath = () => `${a.user.id}/${randomUUID()}`;
let path;

await step('three users with published keys', async () => {
  a = await user('a'); b = await user('b'); c = await user('c');
});

await step('contact request is pending until accepted', async () => {
  const req = await rpc(a, 'request_contact', { p_username: b.name });
  assert.equal(req.json[0].state, 'pending_out');
  const early = await rpc(a, 'contact_devices', { p_user_id: b.user.id });
  assert.equal(early.status, 403);
  assert.equal((await rpc(b, 'accept_contact', { p_user_id: a.user.id })).status, 204);
  const list = await rpc(a, 'list_contacts', {});
  assert.equal(list.json.find((x) => x.user_id === b.user.id).state, 'accepted');
});

await step('bundle claim pops one-time keys; strangers get nothing', async () => {
  const one = await rpc(a, 'claim_prekey_bundle', { p_device_id: b.device });
  const two = await rpc(a, 'claim_prekey_bundle', { p_device_id: b.device });
  assert.equal(one.status, 200);
  assert.notEqual(one.json[0].prekey_id, two.json[0].prekey_id);
  assert.equal(Buffer.from(one.json[0].kyber_key, 'base64').length, 1569);
  assert.equal((await rpc(c, 'claim_prekey_bundle', { p_device_id: b.device })).status, 403);
});

const envelope = randomBytes(480);
await step('mailbox relays exact bytes to the right device only', async () => {
  const sent = await rpc(a, 'send_messages', { p_from_device: a.device, p_messages: [{ to: b.device, type: 3, body: envelope.toString('base64') }] });
  assert.equal(sent.status, 200, JSON.stringify(sent.json));
  assert.equal((await rpc(c, 'send_messages', { p_from_device: c.device, p_messages: [{ to: b.device, type: 3, body: 'AAAA' }] })).status, 403);
  const peek = await http('GET', '/rest/v1/mailbox?select=id', { token: c.access_token });
  assert.deepEqual(peek.json, []);
  const got = await rpc(b, 'fetch_messages', { p_device: b.device });
  assert.equal(got.json.length, 1);
  assert.equal(got.json[0].body, envelope.toString('base64'));
  assert.equal(got.json[0].from_device, a.device);
  // What the database actually stores (admin view) is the same opaque bytes.
  const stored = await http('GET', `/rest/v1/mailbox?select=envelope&id=eq.${got.json[0].id}`, { key: service, token: service });
  assert.equal(Buffer.from(stored.json[0].envelope.slice(2), 'hex').toString('base64'), envelope.toString('base64'));
  assert.equal((await rpc(b, 'ack_messages', { p_device: b.device, p_ids: [got.json[0].id] })).json, 1);
});

await step('attachments: owner uploads, contact reads, stranger cannot', async () => {
  path = blobPath();
  const blob = randomBytes(2048);
  const up = await http('POST', `/storage/v1/object/attachments/${path}`, { token: a.access_token, raw: blob, contentType: 'application/octet-stream' });
  assert.equal(up.status, 200, `upload ${up.status} ${up.buf.toString().slice(0, 200)}`);
  const asB = await http('GET', `/storage/v1/object/attachments/${path}`, { token: b.access_token });
  assert.equal(asB.status, 200);
  assert.ok(asB.buf.equals(blob));
  const asC = await http('GET', `/storage/v1/object/attachments/${path}`, { token: c.access_token });
  assert.ok(asC.status >= 400, `stranger read ${asC.status}`);
  const anonRead = await http('GET', `/storage/v1/object/attachments/${path}`);
  assert.ok(anonRead.status >= 400, `anon read ${anonRead.status}`);
  const intoB = await http('POST', `/storage/v1/object/attachments/${b.user.id}/${randomUUID()}`,
    { token: a.access_token, raw: blob, contentType: 'application/octet-stream' });
  assert.ok(intoB.status >= 400, `upload into another user's folder ${intoB.status}`);
});

await step('Realtime rings the recipient device on new mail', async () => {
  const wsUrl = url.replace('https://', 'wss://') + `/realtime/v1/websocket?apikey=${anon}&vsn=1.0.0`;
  const ws = new WebSocket(wsUrl);
  const rang = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('no notification within 15 s')), 15000);
    ws.onmessage = async (ev) => {
      const m = JSON.parse(ev.data);
      if (m.event === 'phx_reply' && m.topic === 'realtime:mailbox' && m.payload.status === 'ok' && !ws.sent) {
        ws.sent = true;
        await new Promise((r) => setTimeout(r, 1500)); // let the subscription settle
        await rpc(a, 'send_messages', { p_from_device: a.device, p_messages: [{ to: b.device, type: 2, body: 'AAAA' }] });
      }
      if (m.event === 'postgres_changes') { clearTimeout(timer); resolve(m.payload); }
      if (m.event === 'phx_reply' && m.payload.status === 'error') { clearTimeout(timer); reject(new Error(JSON.stringify(m.payload))); }
    };
  });
  ws.onopen = () => ws.send(JSON.stringify({
    topic: 'realtime:mailbox', event: 'phx_join', ref: '1', join_ref: '1',
    payload: {
      config: { broadcast: { self: false }, presence: { key: '' }, private: false,
        postgres_changes: [{ event: 'INSERT', schema: 'public', table: 'mailbox', filter: `to_device=eq.${b.device}` }] },
      access_token: b.access_token,
    },
  }));
  try { await rang; } finally { ws.close(); }
});

await step('deleting an account removes its attachments and its contacts', async () => {
  const r = await http('POST', '/functions/v1/delete-account', { token: a.access_token, body: {} });
  assert.equal(r.status, 200, `delete ${r.status}`);
  const listed = await admin('POST', '/storage/v1/object/list/attachments', { prefix: `${a.user.id}/`, limit: 10 });
  assert.deepEqual(listed.json, []);
  const contacts = await rpc(b, 'list_contacts', {});
  assert.deepEqual(contacts.json, []);
});

for (const s of [a, b, c]) if (s?.user?.id) await admin('DELETE', `/auth/v1/admin/users/${s.user.id}`);

for (const [r, n] of results) console.log(`${r}  ${n}`);
const failed = results.filter(([r]) => r === 'FAIL').length;
console.log(`\n${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
