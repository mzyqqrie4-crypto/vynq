# vynq backend (Supabase)

| Path | What |
|---|---|
| `supabase/migrations/` | Schema, row-level security, RPCs. Applied in filename order. |
| `supabase/functions/delete-account/` | Edge Function: deletes the caller's account (cascades). |
| `supabase/config.toml` | Local-dev config for the Supabase CLI. |
| `tests/` | Policy tests: real Postgres (PGlite, in-process) + a shim for Supabase's `auth` schema. |

## Run the tests
```powershell
cd backend/tests
npm ci
npm test
```
No Docker or Supabase account needed. The shim (`tests/supabase-shim.sql`) only recreates the roles, `auth.users` and `auth.uid()`; everything else is the real migration. Before release the same suite should also run against `supabase start` to catch differences.

## Hosted project setup (once the project exists)
1. **Auth → Providers → Email:** enabled, "Confirm email" off, OTP length 6, expiry 600 s. Phone provider off. No social providers.
2. **Auth → Email templates → Magic Link:** the body must contain `{{ .Token }}` (the 6-digit code), not only the link. The app asks for the code.
3. **Auth → Rate limits:** keep the defaults or lower; OTP sends are also throttled to one per 60 s per address.
4. **Auth → Sessions:** refresh token rotation on, reuse interval 10 s.
5. Apply migrations: `supabase link --project-ref <ref>` then `supabase db push`.
6. Deploy the function: `supabase functions deploy delete-account`.
7. Put the **project URL and anon (publishable) key** in `android/local.properties`:
   ```
   vynq.supabaseUrl=https://<ref>.supabase.co
   vynq.supabaseAnonKey=<anon key>
   ```
   The anon key is designed to ship inside apps; RLS is what protects data. The **service-role key and database password never go in the app or in git.**

## API surface (Phase 2)
| Call | Who | Notes |
|---|---|---|
| `POST /auth/v1/otp` | anyone | Sends a 6-digit code. Creates the user on first sign-in. |
| `POST /auth/v1/verify` (type `email`) | anyone | Exchanges email + code for a session. |
| `POST /auth/v1/logout` | signed in | Revokes the refresh token. |
| `GET /rest/v1/profiles` | signed in | Own row only (RLS). |
| `PATCH /rest/v1/profiles` | signed in | `display_name`, `discoverable` only. |
| `rpc/claim_username` | signed in | Validates, blocks look-alikes and reserved names. 10/hour. |
| `rpc/lookup_username` | signed in | Exact match, discoverable users only, no email. 30 per 10 min. |
| `rpc/register_device`, `rpc/revoke_device` | signed in | Max 5 active devices. Revoked ids can't return. |
| `functions/v1/delete-account` | signed in | Deletes the auth user; rows cascade. |

Errors from RPCs use PostgREST `PTxxx` codes (HTTP status = xxx) with a stable message such as `username_taken`.
