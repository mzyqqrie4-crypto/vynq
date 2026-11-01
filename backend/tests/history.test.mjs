import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { freshDb, createUser, as, errorOf } from './db.mjs';

let db, alice, bob;
const b64 = (n) => randomBytes(n).toString('base64');
const call = (uid, sql, params = []) => as(db, uid, async (tx) => (await tx.query(sql, params)).rows);
const append = (uid, records) => call(uid, 'select public.append_history($1) as n', [JSON.stringify(records)]);

before(async () => {
  db = await freshDb();
  alice = await createUser(db, 'alice@example.test');
  bob = await createUser(db, 'bob@example.test');
});

test('history needs a vault first', async () => {
  assert.match((await errorOf(append(alice, [{ id: 'm1', blob: b64(60) }]))).message, /vault_missing/);
  assert.deepEqual(await call(alice, 'select * from public.get_history_vault()'), []);
});

test('the first phone creates the vault; a second create is refused', async () => {
  const wrapped = b64(60);
  await call(alice, 'select public.set_history_vault($1)', [wrapped]);
  const [v] = await call(alice, 'select * from public.get_history_vault()');
  assert.equal(v.wrapped_key, wrapped);
  assert.match((await errorOf(call(alice, 'select public.set_history_vault($1)', [b64(60)]))).message, /vault_exists/);
});

test('re-wrapping replaces the key; malformed keys are refused', async () => {
  const next = b64(60);
  await call(alice, 'select public.set_history_vault($1, true)', [next]);
  assert.equal((await call(alice, 'select * from public.get_history_vault()'))[0].wrapped_key, next);
  assert.match((await errorOf(call(alice, 'select public.set_history_vault($1, true)', [b64(8)]))).message, /invalid_vault/);
  assert.match((await errorOf(call(bob, 'select public.set_history_vault($1, true)', [b64(60)]))).message, /vault_missing/);
});

test('records append once and come back in order, paged', async () => {
  const records = Array.from({ length: 5 }, (_, i) => ({ id: `m${i}`, blob: b64(100 + i) }));
  assert.equal((await append(alice, records))[0].n, 5);
  assert.equal((await append(alice, records.slice(0, 2)))[0].n, 0); // duplicates ignored
  const page1 = await call(alice, 'select * from public.fetch_history(0, 3)');
  assert.deepEqual(page1.map((r) => r.record_id), ['m0', 'm1', 'm2']);
  const page2 = await call(alice, 'select * from public.fetch_history($1, 3)', [page1.at(-1).seq]);
  assert.deepEqual(page2.map((r) => r.record_id), ['m3', 'm4']);
  assert.equal(page1[0].blob, records[0].blob);
  assert.equal(Number((await call(alice, 'select public.history_count() as n'))[0].n), 5);
});

test("nobody reads or writes another user's history", async () => {
  await call(bob, 'select public.set_history_vault($1)', [b64(60)]);
  assert.deepEqual(await call(bob, 'select * from public.fetch_history(0, 100)'), []);
  for (const t of ['history', 'history_vault']) {
    assert.match((await errorOf(call(bob, `select * from public.${t}`))).message, /permission denied/);
  }
  assert.match((await errorOf(call(bob, "insert into public.history (user_id, record_id, blob) values ($1, 'x', '\\x00')", [alice]))).message, /permission denied/);
  assert.match((await errorOf(call(null, 'select * from public.get_history_vault()'))).message, /permission denied/);
});

test('record limits are enforced', async () => {
  assert.match((await errorOf(append(alice, [{ id: 'tiny', blob: b64(4) }]))).message, /invalid_records/);
  assert.match((await errorOf(append(alice, [{ id: 'huge', blob: b64(70000) }]))).message, /invalid_records/);
  assert.match((await errorOf(append(alice, Array.from({ length: 101 }, (_, i) => ({ id: `x${i}`, blob: b64(40) }))))).message, /invalid_records/);
});

test('deleting the account deletes its history and vault', async () => {
  await db.query('delete from auth.users where id = $1', [alice]);
  const { rows } = await db.query(`select
    (select count(*) from public.history where user_id = $1)::int as h,
    (select count(*) from public.history_vault where user_id = $1)::int as v`, [alice]);
  assert.deepEqual(rows[0], { h: 0, v: 0 });
});
