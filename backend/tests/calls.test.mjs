import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { freshDb, createUser, as, errorOf } from './db.mjs';

let db, alice, bob, carol, callId;
const call = (uid, sql, params = []) => as(db, uid, async (tx) => (await tx.query(sql, params)).rows);

before(async () => {
  db = await freshDb();
  alice = await createUser(db, 'alice@example.test');
  bob = await createUser(db, 'bob@example.test');
  carol = await createUser(db, 'carol@example.test');
  for (const [u, n] of [[alice, 'alice'], [bob, 'bob'], [carol, 'carol']]) await call(u, 'select public.claim_username($1)', [n]);
  await call(alice, 'select * from public.request_contact($1)', ['bob']);
  await call(bob, 'select public.accept_contact($1)', [alice]);
});

test('only accepted contacts can be called', async () => {
  assert.match((await errorOf(call(alice, 'select public.create_call($1)', [carol]))).message, /not_a_contact/);
  const [r] = await call(alice, 'select public.create_call($1, false) as id', [bob]);
  callId = r.id;
  assert.ok(callId);
});

test('both participants see the call; nobody else does', async () => {
  assert.equal((await call(alice, 'select * from public.calls')).length, 1);
  assert.equal((await call(bob, 'select * from public.calls')).length, 1);
  assert.equal((await call(carol, 'select * from public.calls')).length, 0);
  assert.match((await errorOf(call(alice, "insert into public.calls (caller_id, callee_id) values ($1, $2)", [alice, bob]))).message, /permission denied/);
});

test('membership is what the token function checks', async () => {
  const member = async (u) => (await db.query('select public.call_member($1, $2) as m', [callId, u])).rows[0].m;
  assert.equal(await member(alice), true);
  assert.equal(await member(bob), true);
  assert.equal(await member(carol), false);
  assert.match((await errorOf(call(alice, 'select public.call_member($1, $2)', [callId, alice]))).message, /permission denied/);
});

test('answer and end are recorded; an ended call grants no more access', async () => {
  await call(bob, "select public.update_call($1, 'answered')", [callId]);
  await call(alice, "select public.update_call($1, 'ended')", [callId]);
  const { rows } = await db.query('select answered_at, ended_at from public.calls where call_id = $1', [callId]);
  assert.ok(rows[0].answered_at && rows[0].ended_at);
  assert.equal((await db.query('select public.call_member($1, $2) as m', [callId, alice])).rows[0].m, false);
  assert.match((await errorOf(call(carol, "select public.update_call($1, 'ended')", [callId]))).message, /call_not_found/);
});

test('removing a contact also cuts off an ongoing call', async () => {
  const [r] = await call(alice, 'select public.create_call($1) as id', [bob]);
  await call(bob, 'select public.remove_contact($1)', [alice]);
  assert.equal((await db.query('select public.call_member($1, $2) as m', [r.id, alice])).rows[0].m, false);
});
