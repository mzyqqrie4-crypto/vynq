-- Message history that follows the account across phones. Each record is
-- encrypted on a phone with the account's history key, which the server never
-- has: phones get it from another of the user's phones (over the Signal
-- channel, after the user approves) or by unwrapping `wrapped_key` with the
-- recovery key. Everything here goes when the account is deleted.

create table public.history_vault (
  user_id     uuid primary key references auth.users (id) on delete cascade,
  wrapped_key bytea       not null check (octet_length(wrapped_key) between 40 and 200),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table public.history (
  seq        bigint generated always as identity primary key,
  user_id    uuid        not null references auth.users (id) on delete cascade,
  record_id  text        not null check (char_length(record_id) between 1 and 64),
  blob       bytea       not null check (octet_length(blob) between 28 and 65536),
  created_at timestamptz not null default now(),
  unique (user_id, record_id)
);

create index history_user_seq_idx on public.history (user_id, seq);

alter table public.history_vault enable row level security;
alter table public.history enable row level security;
revoke all on public.history_vault, public.history from public, anon, authenticated;

-- The wrapped key (useless without the recovery key). Absent until the account's first phone sets it up.
create function public.get_history_vault()
returns table (wrapped_key text, created_at timestamptz)
language sql security definer stable
set search_path = ''
as $$
  select private.b64(v.wrapped_key), v.created_at from public.history_vault v where v.user_id = auth.uid()
$$;

-- Creates the vault, or (p_replace) re-wraps it, e.g. after a new recovery key.
-- Errors: vault_exists (someone else's phone set it up first), invalid_vault.
create function public.set_history_vault(p_wrapped_key text, p_replace boolean default false)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  perform private.hit_rate_limit(v_uid::text, 'set_history_vault', 10, interval '1 hour');
  if p_replace then
    update public.history_vault set wrapped_key = private.unb64(p_wrapped_key), updated_at = now() where user_id = v_uid;
    if not found then
      raise exception 'vault_missing' using errcode = 'PT404';
    end if;
  else
    insert into public.history_vault (user_id, wrapped_key) values (v_uid, private.unb64(p_wrapped_key))
    on conflict (user_id) do nothing;
    if not found then
      raise exception 'vault_exists' using errcode = 'PT409';
    end if;
  end if;
exception when check_violation then
  raise exception 'invalid_vault' using errcode = 'PT422';
end;
$$;

-- p_records: [{"id": text, "blob": b64}, ...], at most 100. Duplicates are ignored.
create function public.append_history(p_records jsonb)
returns integer language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid   uuid := private.require_user();
  v_count integer;
begin
  if jsonb_typeof(p_records) <> 'array' or jsonb_array_length(p_records) not between 1 and 100 then
    raise exception 'invalid_records' using errcode = 'PT422';
  end if;
  if not exists (select 1 from public.history_vault where user_id = v_uid) then
    raise exception 'vault_missing' using errcode = 'PT409';
  end if;
  perform private.hit_rate_limit(v_uid::text, 'append_history', 600, interval '1 hour');
  insert into public.history (user_id, record_id, blob)
  select v_uid, r->>'id', private.unb64(r->>'blob') from jsonb_array_elements(p_records) r
  on conflict (user_id, record_id) do nothing;
  get diagnostics v_count = row_count;
  return v_count;
exception when check_violation or not_null_violation then
  raise exception 'invalid_records' using errcode = 'PT422';
end;
$$;

create function public.fetch_history(p_after bigint default 0, p_limit integer default 200)
returns table (seq bigint, record_id text, blob text)
language plpgsql security definer stable
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  return query
    select h.seq, h.record_id, private.b64(h.blob) from public.history h
     where h.user_id = v_uid and h.seq > coalesce(p_after, 0)
     order by h.seq
     limit least(greatest(p_limit, 1), 500);
end;
$$;

create function public.history_count()
returns bigint language sql security definer stable
set search_path = ''
as $$ select count(*) from public.history where user_id = auth.uid() $$;

revoke execute on all functions in schema private from public, anon, authenticated;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.get_history_vault()', 'public.set_history_vault(text, boolean)',
    'public.append_history(jsonb)', 'public.fetch_history(bigint, integer)', 'public.history_count()'
  ] loop
    execute format('revoke execute on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end;
$$;
