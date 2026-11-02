import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { freshDb, createUser, as, errorOf } from './db.mjs';

let db, alice, bob, carol;
const b64 = (n) => randomBytes(n).toString('base64');
const call = (uid, sql, params = []) => as(db, uid, async (tx) => (await tx.query(sql, params)).rows);

async function setupUser(email, name) {
  const uid = await createUser(db, email);
  await call(uid, 'select public.claim_username($1)', [name]);
  return uid;
}

before(async () => {
  db = await freshDb();
  alice = await setupUser('alice@example.test', 'alice');
  bob = await setupUser('bob@example.test', 'bob');
  carol = await setupUser('carol@example.test', 'carol');
  await call(alice, 'select public.request_contact($1)', ['bob']);
  await call(bob, 'select public.accept_contact($1)', [alice]);
  await call(alice, 'select public.request_contact($1)', ['carol']); // carol never accepts
});

test('a profile picture path can only point into your own folder', async () => {
  await call(alice, 'select public.set_avatar($1)', [`${alice}/a1`]);
  assert.equal((await call(alice, 'select public.my_avatar() as p'))[0].p, `${alice}/a1`);
  assert.match((await errorOf(call(alice, 'select public.set_avatar($1)', [`${bob}/a1`]))).message, /invalid_avatar_path/);
  assert.match((await errorOf(call(alice, 'select public.set_avatar($1)', ['x']))).message, /avatar_path/);
});

test('the path column itself is not writable directly', async () => {
  assert.ok(await errorOf(call(alice, 'update public.profiles set avatar_path = $1 where user_id = $2', [`${alice}/z`, alice])));
});

test('only accepted contacts see the picture path', async () => {
  const forBob = await call(bob, 'select * from public.list_contacts()');
  assert.equal(forBob.find((r) => r.user_id === alice).avatar_path, `${alice}/a1`);
  const forCarol = await call(carol, 'select * from public.list_contacts()');
  assert.equal(forCarol.find((r) => r.user_id === alice).avatar_path, null);   // request pending
});

test('clearing the picture removes the path', async () => {
  await call(alice, 'select public.set_avatar(null)');
  assert.equal((await call(alice, 'select public.my_avatar() as p'))[0].p, null);
  const forBob = await call(bob, 'select * from public.list_contacts()');
  assert.equal(forBob.find((r) => r.user_id === alice).avatar_path, null);
});

test('deleting history removes only your own records', async () => {
  for (const uid of [alice, bob]) {
    await call(uid, 'select public.set_history_vault($1)', [b64(60)]);
    await call(uid, 'select public.append_history($1)', [JSON.stringify([{ id: 'm1', blob: b64(80) }, { id: 'm2', blob: b64(80) }])]);
  }
  assert.equal((await call(alice, 'select public.delete_history($1) as n', [['m1', 'nope']]))[0].n, 1);
  assert.deepEqual((await call(alice, 'select record_id from public.fetch_history()')).map((r) => r.record_id), ['m2']);
  assert.equal(Number((await call(bob, 'select public.history_count() as n'))[0].n), 2);           // bob's untouched
  assert.match((await errorOf(call(alice, 'select public.delete_history($1)', [Array(501).fill('x')]))).message, /invalid_ids/);
});
