import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { freshDb, createUser, as, errorOf } from './db.mjs';

let db, alice, bob;

before(async () => {
  db = await freshDb();
  alice = await createUser(db, 'alice@example.test');
  bob = await createUser(db, 'bob@example.test');
});

const claim = (uid, name) =>
  as(db, uid, (tx) => tx.query('select public.claim_username($1) as u', [name]));

// --- profiles ---------------------------------------------------------------

test('a profile is created for every new auth user', async () => {
  const { rows } = await db.query('select count(*)::int as n from public.profiles');
  assert.equal(rows[0].n, 2);
});

test('users read only their own profile', async () => {
  const rows = await as(db, alice, async (tx) => (await tx.query('select user_id from public.profiles')).rows);
  assert.deepEqual(rows.map((r) => r.user_id), [alice]);
});

test('anon cannot read profiles', async () => {
  const e = await errorOf(as(db, null, (tx) => tx.query('select * from public.profiles')));
  assert.match(e.message, /permission denied/);
});

test('users can edit display name but not username columns directly', async () => {
  await as(db, alice, (tx) => tx.query("update public.profiles set display_name = 'Alice' where user_id = $1", [alice]));
  const e = await errorOf(
    as(db, alice, (tx) => tx.query("update public.profiles set username = 'sneaky' where user_id = $1", [alice])),
  );
  assert.match(e.message, /permission denied/);
});

test("users cannot change someone else's profile", async () => {
  await as(db, alice, (tx) => tx.query("update public.profiles set display_name = 'pwned' where user_id = $1", [bob]));
  const { rows } = await db.query('select display_name from public.profiles where user_id = $1', [bob]);
  assert.equal(rows[0].display_name, null);
});

test('display names reject control characters', async () => {
  const e = await errorOf(
    as(db, alice, (tx) => tx.query("update public.profiles set display_name = E'a\\u0007b' where user_id = $1", [alice])),
  );
  assert.match(e.message, /display_name_printable/);
});

// --- usernames --------------------------------------------------------------

test('claiming a username normalizes it', async () => {
  const { rows } = await claim(alice, '  Alice.Smith ');
  assert.equal(rows[0].u, 'alice.smith');
});

test('invalid usernames are rejected', async () => {
  for (const bad of ['ab', 'a'.repeat(21), '.alice', 'alice_', 'al..ice', 'al._ice', 'älice', 'al ice', 'al-ice']) {
    const e = await errorOf(claim(bob, bad));
    assert.match(e.message, /invalid_username/, bad);
  }
});

test('usernames are unique', async () => {
  const e = await errorOf(claim(bob, 'alice.smith'));
  assert.match(e.message, /username_taken/);
});

test('look-alike usernames are blocked (skeleton collision)', async () => {
  for (const lookalike of ['alicesmith', 'a1ice_smith', 'alice.5mith']) {
    const e = await errorOf(claim(bob, lookalike));
    assert.match(e.message, /username_taken/, lookalike);
  }
});

test('reserved names and their look-alikes are blocked', async () => {
  for (const name of ['admin', 'ADMIN', 'adm1n', 'vynq', 'v.y.n.q', 'supp0rt', 'su_pport', 'rnod']) {
    const e = await errorOf(claim(bob, name));
    assert.match(e.message, /username_reserved/, name);
  }
});

test('stored skeletons match the skeleton function used for reserved names', async () => {
  const { rows } = await db.query(
    'select count(*)::int as n from public.profiles where username is not null and username_skeleton <> private.username_skeleton(username)',
  );
  assert.equal(rows[0].n, 0);
});

test('anon cannot claim a username', async () => {
  const e = await errorOf(as(db, null, (tx) => tx.query("select public.claim_username('ghost')")));
  assert.match(e.message, /permission denied/);
});

test('claim_username is rate limited', async () => {
  const carol = await createUser(db, 'carol@example.test');
  for (let i = 0; i < 10; i++) await claim(carol, `carol${i}x`);
  const e = await errorOf(claim(carol, 'carolfinal'));
  assert.match(e.message, /rate_limited/);
});

// --- lookup -----------------------------------------------------------------

test('lookup finds exact, discoverable usernames only, and never exposes email', async () => {
  await claim(bob, 'bob');
  const found = await as(db, alice, async (tx) => (await tx.query("select * from public.lookup_username('BOB')")).rows);
  assert.equal(found.length, 1);
  assert.deepEqual(Object.keys(found[0]).sort(), ['display_name', 'user_id', 'username']);

  const prefix = await as(db, alice, async (tx) => (await tx.query("select * from public.lookup_username('bo')")).rows);
  assert.equal(prefix.length, 0);

  await as(db, bob, (tx) => tx.query('update public.profiles set discoverable = false where user_id = $1', [bob]));
  const hidden = await as(db, alice, async (tx) => (await tx.query("select * from public.lookup_username('bob')")).rows);
  assert.equal(hidden.length, 0);
});

test('lookup is rate limited', async () => {
  const dave = await createUser(db, 'dave@example.test');
  for (let i = 0; i < 30; i++) await as(db, dave, (tx) => tx.query("select * from public.lookup_username('x')"));
  const e = await errorOf(as(db, dave, (tx) => tx.query("select * from public.lookup_username('x')")));
  assert.match(e.message, /rate_limited/);
});

// --- devices ----------------------------------------------------------------

const register = (uid, id, name = 'Phone') =>
  as(db, uid, (tx) => tx.query("select public.register_device($1, 'android', $2)", [id, name]));

test('users register and see only their own devices', async () => {
  const a1 = randomUUID();
  await register(alice, a1, 'Xiaomi 17 Pro');
  await register(bob, randomUUID());
  const rows = await as(db, alice, async (tx) => (await tx.query('select device_id, name from public.devices')).rows);
  assert.deepEqual(rows, [{ device_id: a1, name: 'Xiaomi 17 Pro' }]);
});

test('re-registering the same device refreshes it instead of duplicating', async () => {
  const id = randomUUID();
  await register(alice, id, 'Old name');
  await register(alice, id, 'New name');
  const { rows } = await db.query('select name from public.devices where device_id = $1', [id]);
  assert.deepEqual(rows, [{ name: 'New name' }]);
});

test("a device id can't be taken over by another user", async () => {
  const id = randomUUID();
  await register(alice, id);
  const e = await errorOf(register(bob, id));
  assert.match(e.message, /device_unavailable/);
});

test('devices cannot be inserted or edited directly', async () => {
  const e1 = await errorOf(
    as(db, alice, (tx) =>
      tx.query("insert into public.devices (device_id, user_id, platform, name) values ($1, $2, 'android', 'x')", [randomUUID(), alice]),
    ),
  );
  assert.match(e1.message, /permission denied/);
  const e2 = await errorOf(as(db, alice, (tx) => tx.query("update public.devices set name = 'x'")));
  assert.match(e2.message, /permission denied/);
});

test('a revoked device cannot re-register', async () => {
  const id = randomUUID();
  await register(alice, id);
  await as(db, alice, (tx) => tx.query('select public.revoke_device($1)', [id]));
  const e = await errorOf(register(alice, id));
  assert.match(e.message, /device_unavailable/);
});

test("users cannot revoke someone else's device", async () => {
  const id = randomUUID();
  await register(bob, id);
  const e = await errorOf(as(db, alice, (tx) => tx.query('select public.revoke_device($1)', [id])));
  assert.match(e.message, /device_not_found/);
});

test('at most 5 active devices per user', async () => {
  const erin = await createUser(db, 'erin@example.test');
  for (let i = 0; i < 5; i++) await register(erin, randomUUID());
  const e = await errorOf(register(erin, randomUUID()));
  assert.match(e.message, /device_limit/);
});

// --- private schema & deletion ----------------------------------------------

test('the private schema is unreachable from the API roles', async () => {
  for (const q of ['select * from private.rate_limits', "select private.username_skeleton('x')"]) {
    const e = await errorOf(as(db, alice, (tx) => tx.query(q)));
    assert.match(e.message, /permission denied/, q);
  }
});

test('deleting the auth user removes profile and devices', async () => {
  const frank = await createUser(db, 'frank@example.test');
  await claim(frank, 'frank');
  await register(frank, randomUUID());
  await db.query('delete from auth.users where id = $1', [frank]);
  const { rows } = await db.query(
    'select (select count(*) from public.profiles where user_id = $1)::int as p, (select count(*) from public.devices where user_id = $1)::int as d',
    [frank],
  );
  assert.deepEqual(rows[0], { p: 0, d: 0 });
});
