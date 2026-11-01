import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { freshDb, createUser, as, errorOf } from './db.mjs';

let db, alice, bob, carol;
const dev = {};

const b64 = (n) => randomBytes(n).toString('base64');
const call = (uid, sql, params = []) => as(db, uid, async (tx) => (await tx.query(sql, params)).rows);

async function setupUser(email, name) {
  const uid = await createUser(db, email);
  await call(uid, 'select public.claim_username($1)', [name]);
  return uid;
}

async function addDevice(uid, { keys = true, otks = 3 } = {}) {
  const id = randomUUID();
  await call(uid, "select public.register_device($1, 'android', 'Phone')", [id]);
  if (keys) {
    const oneTime = Array.from({ length: otks }, (_, i) => ({ id: i + 1, key: b64(33) }));
    await call(uid, 'select public.publish_keys($1, $2, $3, $4, $5, $6)', [
      id, b64(33), 1234,
      { id: 1, key: b64(33), sig: b64(64) },
      { id: 1, key: b64(1569), sig: b64(64) },
      JSON.stringify(oneTime),
    ]);
  }
  return id;
}

before(async () => {
  db = await freshDb();
  alice = await setupUser('alice@example.test', 'alice');
  bob = await setupUser('bob@example.test', 'bob');
  carol = await setupUser('carol@example.test', 'carol');
  dev.alice = await addDevice(alice);
  dev.bob = await addDevice(bob);
  dev.carol = await addDevice(carol);
});

const send = (uid, from, to, body = b64(100), type = 3) =>
  call(uid, 'select public.send_messages($1, $2) as n', [from, JSON.stringify([{ to, type, body }])]);

// --- contacts ---------------------------------------------------------------

test('strangers cannot message, fetch bundles or list devices', async () => {
  assert.match((await errorOf(send(alice, dev.alice, dev.bob))).message, /device_unavailable/);
  assert.match((await errorOf(call(alice, 'select * from public.claim_prekey_bundle($1)', [dev.bob]))).message, /device_unavailable/);
  assert.match((await errorOf(call(alice, 'select * from public.contact_devices($1)', [bob]))).message, /not_a_contact/);
});

test('a request is pending until the other side accepts', async () => {
  const [r] = await call(alice, 'select * from public.request_contact($1)', ['Bob']);
  assert.equal(r.state, 'pending_out');
  assert.equal(r.user_id, bob);
  const [b] = await call(bob, 'select * from public.list_contacts()');
  assert.equal(b.state, 'pending_in');
  assert.equal(b.username, 'alice');
  // Still not allowed while pending.
  assert.match((await errorOf(send(alice, dev.alice, dev.bob))).message, /device_unavailable/);

  await call(bob, 'select public.accept_contact($1)', [alice]);
  const [a] = await call(alice, 'select * from public.list_contacts()');
  assert.equal(a.state, 'accepted');
});

test('asking someone who already asked you accepts both sides', async () => {
  await call(carol, 'select * from public.request_contact($1)', ['alice']);
  const [r] = await call(alice, 'select * from public.request_contact($1)', ['carol']);
  assert.equal(r.state, 'accepted');
  const rows = await call(carol, 'select * from public.list_contacts()');
  assert.equal(rows.find((x) => x.user_id === alice).state, 'accepted');
});

test("contact rows are private and can't be written directly", async () => {
  const rows = await call(bob, 'select owner_id from public.contacts');
  assert.ok(rows.every((r) => r.owner_id === bob));
  const e = await errorOf(call(bob, "insert into public.contacts values ($1, $2, 'accepted')", [bob, carol]));
  assert.match(e.message, /permission denied/);
});

test('cannot add yourself or unknown users', async () => {
  assert.match((await errorOf(call(alice, 'select * from public.request_contact($1)', ['alice']))).message, /cannot_add_self/);
  assert.match((await errorOf(call(alice, 'select * from public.request_contact($1)', ['nobody']))).message, /user_not_found/);
});

test('only the receiving side can accept', async () => {
  const dave = await setupUser('dave@example.test', 'dave');
  await call(dave, 'select * from public.request_contact($1)', ['bob']);
  assert.match((await errorOf(call(dave, 'select public.accept_contact($1)', [bob]))).message, /request_not_found/);
});

// --- keys -------------------------------------------------------------------

test('key tables are never readable directly', async () => {
  for (const t of ['signed_prekeys', 'kyber_prekeys', 'one_time_prekeys']) {
    assert.match((await errorOf(call(bob, `select * from public.${t}`))).message, /permission denied/);
  }
});

test("a contact's bundle pops one one-time pre-key at a time", async () => {
  const [b1] = await call(alice, 'select * from public.claim_prekey_bundle($1)', [dev.bob]);
  const [b2] = await call(alice, 'select * from public.claim_prekey_bundle($1)', [dev.bob]);
  assert.equal(b1.user_id, bob);
  assert.equal(b1.registration_id, 1234);
  assert.equal(Buffer.from(b1.identity_key, 'base64').length, 33);
  assert.equal(Buffer.from(b1.kyber_key, 'base64').length, 1569);
  assert.notEqual(b1.prekey_id, b2.prekey_id);
  const [s] = await call(bob, 'select * from public.prekey_status($1)', [dev.bob]);
  assert.equal(s.one_time_count, 1);
});

test('when one-time pre-keys run out the bundle still works without one', async () => {
  await call(alice, 'select * from public.claim_prekey_bundle($1)', [dev.bob]);
  const [b] = await call(alice, 'select * from public.claim_prekey_bundle($1)', [dev.bob]);
  assert.equal(b.prekey_id, null);
  assert.ok(b.signed_key && b.kyber_key);
});

test('a device identity key can never be replaced', async () => {
  const e = await errorOf(call(bob, 'select public.publish_keys($1, $2, 1, null, null, null)', [dev.bob, b64(33)]));
  assert.match(e.message, /identity_immutable/);
});

test("nobody can publish keys for another user's device", async () => {
  const e = await errorOf(call(alice, 'select public.publish_keys($1, null, null, null, null, $2)', [dev.bob, '[]']));
  assert.match(e.message, /device_unavailable/);
});

test('malformed keys are rejected', async () => {
  const e = await errorOf(call(bob, 'select public.publish_keys($1, null, null, $2, null, null)',
    [dev.bob, { id: 2, key: b64(10), sig: b64(64) }]));
  assert.match(e.message, /invalid_keys/);
});

test('contact_devices lists only key-ready active devices', async () => {
  const bare = await addDevice(bob, { keys: false });
  const rows = await call(alice, 'select * from public.contact_devices($1)', [bob]);
  assert.deepEqual(rows.map((r) => r.device_id), [dev.bob]);
  assert.ok(!rows.some((r) => r.device_id === bare));
});

// --- mailbox ----------------------------------------------------------------

test('contacts can send; only the recipient device can fetch and ack', async () => {
  const body = b64(200);
  const [r] = await send(alice, dev.alice, dev.bob, body);
  assert.equal(r.n, 1);

  // Bob's device sees it with the sender's address; Carol sees nothing.
  const got = await call(bob, 'select * from public.fetch_messages($1)', [dev.bob]);
  assert.equal(got.length, 1);
  assert.equal(got[0].body, body);
  assert.equal(got[0].from_user, alice);
  assert.equal(got[0].from_device, dev.alice);
  assert.match((await errorOf(call(carol, 'select * from public.fetch_messages($1)', [dev.bob]))).message, /device_unavailable/);
  assert.equal((await call(carol, 'select * from public.mailbox')).length, 0);

  // Ack by someone else does nothing; by the owner deletes.
  assert.match((await errorOf(call(alice, 'select public.ack_messages($1, $2)', [dev.bob, [got[0].id]]))).message, /device_unavailable/);
  const [a] = await call(bob, 'select public.ack_messages($1, $2) as n', [dev.bob, [got[0].id]]);
  assert.equal(a.n, 1);
  assert.equal((await call(bob, 'select * from public.fetch_messages($1)', [dev.bob])).length, 0);
});

test('mailbox stores exactly the bytes sent (opaque ciphertext)', async () => {
  const body = b64(300);
  await send(alice, dev.alice, dev.bob, body);
  const { rows } = await db.query('select envelope from public.mailbox where to_device = $1', [dev.bob]);
  assert.equal(Buffer.from(rows.at(-1).envelope).toString('base64'), body);
});

test('mail cannot be written directly or spoofed as another device', async () => {
  const e = await errorOf(call(alice,
    "insert into public.mailbox (to_device, from_user, from_device, type, envelope) values ($1, $2, $3, 2, '\\x00')",
    [dev.bob, alice, dev.alice]));
  assert.match(e.message, /permission denied/);
  assert.match((await errorOf(send(alice, dev.carol, dev.bob))).message, /device_unavailable/);
});

test('envelope type and size are checked', async () => {
  assert.match((await errorOf(send(alice, dev.alice, dev.bob, b64(10), 7))).message, /invalid_messages/);
  assert.match((await errorOf(send(alice, dev.alice, dev.bob, b64(70000)))).message, /invalid_messages/);
  assert.match((await errorOf(send(alice, dev.alice, dev.bob, '***not base64***'))).message, /invalid_base64|invalid_messages/);
});

test("users can send to their own other devices but not to themselves on the same device", async () => {
  const second = await addDevice(alice);
  const [r] = await send(alice, dev.alice, second);
  assert.equal(r.n, 1);
  assert.match((await errorOf(send(alice, dev.alice, dev.alice))).message, /device_unavailable/);
});

test('removing a contact cuts off messaging and drops undelivered mail', async () => {
  await send(alice, dev.alice, dev.carol);
  await call(carol, 'select public.remove_contact($1)', [alice]);
  assert.equal((await call(carol, 'select * from public.fetch_messages($1)', [dev.carol])).length, 0);
  assert.match((await errorOf(send(alice, dev.alice, dev.carol))).message, /device_unavailable/);
  assert.equal((await call(alice, 'select * from public.list_contacts()')).some((c) => c.user_id === carol), false);
});

test('revoking a device drops its keys and mail and blocks sending to it', async () => {
  const extra = await addDevice(bob);
  await send(alice, dev.alice, extra);
  await call(bob, 'select public.revoke_device($1)', [extra]);
  const { rows } = await db.query(
    'select (select count(*) from public.mailbox where to_device = $1)::int as m, (select count(*) from public.one_time_prekeys where device_id = $1)::int as k', [extra]);
  assert.deepEqual(rows[0], { m: 0, k: 0 });
  assert.match((await errorOf(send(alice, dev.alice, extra))).message, /device_unavailable/);
});

test('expired mail is purged on fetch', async () => {
  await send(alice, dev.alice, dev.bob);
  await db.query("update public.mailbox set expires_at = now() - interval '1 second' where to_device = $1", [dev.bob]);
  assert.equal((await call(bob, 'select * from public.fetch_messages($1)', [dev.bob])).length, 0);
  const { rows } = await db.query('select count(*)::int as n from public.mailbox where to_device = $1', [dev.bob]);
  assert.equal(rows[0].n, 0);
});

test('deleting an account removes its contacts, keys and mail', async () => {
  const erin = await setupUser('erin@example.test', 'erin');
  const erinDev = await addDevice(erin);
  await call(erin, 'select * from public.request_contact($1)', ['alice']);
  await call(alice, 'select public.accept_contact($1)', [erin]);
  await send(alice, dev.alice, erinDev);
  await send(erin, erinDev, dev.alice);
  await db.query('delete from auth.users where id = $1', [erin]);
  const { rows } = await db.query(`select
      (select count(*) from public.contacts where owner_id = $1 or contact_id = $1)::int as c,
      (select count(*) from public.mailbox where from_user = $1 or to_device = $2)::int as m,
      (select count(*) from public.one_time_prekeys where device_id = $2)::int as k`, [erin, erinDev]);
  assert.deepEqual(rows[0], { c: 0, m: 0, k: 0 });
});

test('anon can call none of the messaging functions', async () => {
  for (const sql of ['select public.list_contacts()', `select public.fetch_messages('${dev.bob}')`]) {
    assert.match((await errorOf(call(null, sql))).message, /permission denied/);
  }
});
