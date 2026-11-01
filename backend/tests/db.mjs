// Spins up an in-memory Postgres (PGlite), applies the Supabase shim and
// every migration in order, and offers helpers to act as a given user.
import { PGlite } from '@electric-sql/pglite';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const migrationsDir = join(here, '..', 'supabase', 'migrations');

export async function freshDb() {
  const db = new PGlite();
  await db.exec(readFileSync(join(here, 'supabase-shim.sql'), 'utf8'));
  for (const file of readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort()) {
    await db.exec(readFileSync(join(migrationsDir, file), 'utf8'));
  }
  return db;
}

export async function createUser(db, email) {
  const { rows } = await db.query('insert into auth.users (email) values ($1) returning id', [email]);
  return rows[0].id;
}

// Runs fn inside a transaction as the `authenticated` role with the given
// user id as JWT subject (or as `anon` when userId is null), then rolls back
// the role switch. Data changes are committed unless fn throws.
export async function as(db, userId, fn) {
  return db.transaction(async (tx) => {
    if (userId) {
      await tx.query("select set_config('request.jwt.claim.sub', $1, true)", [userId]);
      await tx.exec('set local role authenticated');
    } else {
      await tx.exec('set local role anon');
    }
    return fn(tx);
  });
}

export async function errorOf(promise) {
  try {
    await promise;
  } catch (e) {
    return e;
  }
  throw new Error('expected an error, but the call succeeded');
}
