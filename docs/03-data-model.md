# 03 — Backend data model (draft, implemented in Phase 2–3)

Postgres (Supabase). RLS is **enabled and deny-by-default on every table**. `auth.users` holds the email, which is never exposed to other users.

```sql
profiles        (user_id pk → auth.users, username citext unique, username_skeleton text unique,
                 display_name text, avatar_path text, discoverable bool default true,
                 created_at, updated_at)
devices         (device_id uuid pk, user_id fk, platform text, push_token_enc bytea,
                 identity_key bytea, registration_id int, name text,
                 approved_at timestamptz, last_seen_at, revoked_at)
signed_prekeys  (device_id fk, key_id int, public_key bytea, signature bytea, created_at)
one_time_prekeys(device_id fk, key_id int, public_key bytea)        -- consumed atomically via RPC
contacts        (owner_id fk, contact_id fk, state text check in ('pending_out','pending_in','accepted','blocked'),
                 verified_at timestamptz null, created_at)          -- pk(owner_id, contact_id)
mailbox         (id bigserial, to_device fk, envelope bytea, created_at, expires_at)  -- ciphertext only
call_sessions   (call_id uuid, room text, created_by fk, created_at, ended_at)        -- minimal; no content
rate_limits     (subject text, bucket text, window_start, count)
```

Key RLS rules:
- `profiles`: a user can read their own row. Others can only `select username, display_name, avatar_path` through the `lookup_username(exact)` RPC, which is rate-limited and only matches `discoverable` profiles.
- `devices`: the owner has full access. Contacts in the `accepted` state can read the public key fields of non-revoked devices.
- `mailbox`: insert only through an RPC that checks the sender↔recipient contact state is `accepted`. Select/delete only for `to_device` owned by `auth.uid()`.
- `one_time_prekeys`: never selected directly. The `claim_prekey(device_id)` RPC pops one atomically.

Edge Functions: `livekit-token` (checks call membership and mints a short-lived room-scoped token), `push-fanout` (sends data-only pushes), `delete-account` (cascades and purges storage).
