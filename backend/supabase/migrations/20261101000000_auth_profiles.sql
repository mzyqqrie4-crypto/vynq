-- Phase 2: profiles, usernames, devices, rate limits.
-- Deny by default: RLS on every table, privileges granted column by column,
-- and anything that needs validation goes through a SECURITY DEFINER RPC.

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Usernames
-- ASCII only (a-z 0-9 . _), 3-20 chars, starting and ending with a letter or
-- digit, no two separators in a row. Restricting to ASCII removes Unicode
-- homoglyph impersonation; the skeleton below catches ASCII look-alikes.
-- ---------------------------------------------------------------------------

create function private.normalize_username(raw text)
returns text language sql immutable strict
set search_path = ''
as $$ select lower(btrim(raw)) $$;

create function private.username_is_valid(u text)
returns boolean language sql immutable strict
set search_path = ''
as $$
  select u ~ '^[a-z0-9][a-z0-9._]{1,18}[a-z0-9]$' and u !~ '[._]{2}'
$$;

-- Two usernames with the same skeleton look alike, so only one may exist.
-- Separators are dropped (john.doe = johndoe), then confusable glyphs fold.
-- profiles.username_skeleton repeats this expression inline (constraints and
-- generated columns run with the caller's privileges, and API roles can't
-- reach this schema); a test asserts the two stay identical.
create function private.username_skeleton(u text)
returns text language sql immutable strict
set search_path = ''
as $$
  select replace(replace(replace(
           translate(lower(u), '015i._', 'olsl'),
         'rn', 'm'), 'vv', 'w'), 'cl', 'd')
$$;

create table private.reserved_usernames (skeleton text primary key);
insert into private.reserved_usernames (skeleton)
select private.username_skeleton(name) from unnest(array[
  'vynq', 'admin', 'administrator', 'root', 'system', 'support', 'help',
  'security', 'official', 'staff', 'team', 'moderator', 'mod', 'info',
  'contact', 'abuse', 'noreply', 'postmaster', 'privacy', 'legal', 'api'
]) as name;

-- ---------------------------------------------------------------------------
-- Rate limiting (fixed window, per subject + bucket)
-- ---------------------------------------------------------------------------

create table private.rate_limits (
  subject      text        not null,
  bucket       text        not null,
  window_start timestamptz not null,
  count        integer     not null default 0,
  primary key (subject, bucket)
);

-- Counts one hit; raises rate_limited once the window's budget is spent.
create function private.hit_rate_limit(p_subject text, p_bucket text, p_max integer, p_window interval)
returns void language plpgsql
set search_path = ''
as $$
declare
  v_count integer;
begin
  insert into private.rate_limits as r (subject, bucket, window_start, count)
  values (p_subject, p_bucket, now(), 1)
  on conflict (subject, bucket) do update
    set window_start = case when r.window_start <= now() - p_window then now() else r.window_start end,
        count        = case when r.window_start <= now() - p_window then 1 else r.count + 1 end
  returning count into v_count;

  if v_count > p_max then
    raise exception 'rate_limited' using errcode = 'PT429', hint = p_bucket;
  end if;
end;
$$;

create function private.require_user()
returns uuid language plpgsql stable
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = 'PT401';
  end if;
  return v_uid;
end;
$$;

-- ---------------------------------------------------------------------------
-- Profiles
-- ---------------------------------------------------------------------------

create table public.profiles (
  user_id           uuid primary key references auth.users (id) on delete cascade,
  username          text unique,
  username_skeleton text unique generated always as (
    replace(replace(replace(translate(lower(username), '015i._', 'olsl'), 'rn', 'm'), 'vv', 'w'), 'cl', 'd')
  ) stored,
  display_name      text,
  discoverable      boolean     not null default true,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  constraint username_format check (
    username ~ '^[a-z0-9][a-z0-9._]{1,18}[a-z0-9]$' and username !~ '[._]{2}'),
  constraint display_name_length check (display_name is null or char_length(display_name) between 1 and 40),
  constraint display_name_printable check (display_name is null or display_name !~ '[[:cntrl:]]')
);

alter table public.profiles enable row level security;

create policy "read own profile" on public.profiles
  for select to authenticated using (user_id = auth.uid());

create policy "update own profile" on public.profiles
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

revoke all on public.profiles from public, anon, authenticated;
grant select on public.profiles to authenticated;
-- Username changes go through claim_username(); only these are self-editable.
grant update (display_name, discoverable) on public.profiles to authenticated;

create function private.touch_updated_at()
returns trigger language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger profiles_touch before update on public.profiles
  for each row execute function private.touch_updated_at();

-- Every new auth user gets an empty profile; the username is claimed later.
create function private.create_profile_for_new_user()
returns trigger language plpgsql security definer
set search_path = ''
as $$
begin
  insert into public.profiles (user_id) values (new.id);
  return new;
end;
$$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function private.create_profile_for_new_user();

-- Claim or change the caller's username. Errors: invalid_username,
-- username_reserved, username_taken, rate_limited, not_authenticated.
create function public.claim_username(p_username text)
returns text language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid      uuid := private.require_user();
  v_name     text := private.normalize_username(p_username);
  v_skeleton text;
begin
  perform private.hit_rate_limit(v_uid::text, 'claim_username', 10, interval '1 hour');

  if v_name is null or not private.username_is_valid(v_name) then
    raise exception 'invalid_username' using errcode = 'PT422';
  end if;

  v_skeleton := private.username_skeleton(v_name);
  if exists (select 1 from private.reserved_usernames where skeleton = v_skeleton) then
    raise exception 'username_reserved' using errcode = 'PT409';
  end if;

  begin
    update public.profiles set username = v_name where user_id = v_uid;
  exception when unique_violation then
    raise exception 'username_taken' using errcode = 'PT409';
  end;

  if not found then
    raise exception 'profile_missing' using errcode = 'PT404';
  end if;
  return v_name;
end;
$$;

-- Exact-match lookup of a discoverable user. Never returns email, never
-- does prefix search, and is rate limited against enumeration.
create function public.lookup_username(p_username text)
returns table (user_id uuid, username text, display_name text)
language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  perform private.hit_rate_limit(v_uid::text, 'lookup_username', 30, interval '10 minutes');
  return query
    select p.user_id, p.username, p.display_name
      from public.profiles p
     where p.username = private.normalize_username(p_username)
       and p.discoverable;
end;
$$;

-- ---------------------------------------------------------------------------
-- Devices (key material arrives in Phase 3)
-- ---------------------------------------------------------------------------

create table public.devices (
  device_id    uuid primary key,
  user_id      uuid        not null references auth.users (id) on delete cascade,
  platform     text        not null check (platform in ('android', 'ios')),
  name         text        not null check (char_length(name) between 1 and 40 and name !~ '[[:cntrl:]]'),
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at   timestamptz
);

create index devices_user_idx on public.devices (user_id) where revoked_at is null;

alter table public.devices enable row level security;

create policy "read own devices" on public.devices
  for select to authenticated using (user_id = auth.uid());

revoke all on public.devices from public, anon, authenticated;
grant select on public.devices to authenticated;

-- Registers this install, or refreshes it. A device id belongs to one user
-- forever and a revoked device can't come back. Max 5 active devices.
create function public.register_device(p_device_id uuid, p_platform text, p_name text)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid      uuid := private.require_user();
  v_existing public.devices;
begin
  perform private.hit_rate_limit(v_uid::text, 'register_device', 20, interval '1 hour');

  select * into v_existing from public.devices where device_id = p_device_id for update;

  if found then
    if v_existing.user_id <> v_uid or v_existing.revoked_at is not null then
      raise exception 'device_unavailable' using errcode = 'PT403';
    end if;
    update public.devices
       set name = p_name, last_seen_at = now()
     where device_id = p_device_id;
    return;
  end if;

  if (select count(*) from public.devices where user_id = v_uid and revoked_at is null) >= 5 then
    raise exception 'device_limit' using errcode = 'PT409';
  end if;

  insert into public.devices (device_id, user_id, platform, name)
  values (p_device_id, v_uid, p_platform, p_name);
end;
$$;

create function public.revoke_device(p_device_id uuid)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  update public.devices
     set revoked_at = now()
   where device_id = p_device_id and user_id = v_uid and revoked_at is null;
  if not found then
    raise exception 'device_not_found' using errcode = 'PT404';
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Function privileges: nothing is callable by anon.
-- ---------------------------------------------------------------------------

revoke execute on all functions in schema private from public, anon, authenticated;
revoke execute on function public.claim_username(text) from public, anon;
revoke execute on function public.lookup_username(text) from public, anon;
revoke execute on function public.register_device(uuid, text, text) from public, anon;
revoke execute on function public.revoke_device(uuid) from public, anon;
grant execute on function public.claim_username(text) to authenticated;
grant execute on function public.lookup_username(text) to authenticated;
grant execute on function public.register_device(uuid, text, text) to authenticated;
grant execute on function public.revoke_device(uuid) to authenticated;
