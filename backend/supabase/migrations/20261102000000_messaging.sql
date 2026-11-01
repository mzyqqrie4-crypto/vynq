-- Phase 3: contacts, device keys (Signal protocol pre-keys), encrypted mailbox,
-- attachment storage. The server only ever handles public keys and ciphertext.
-- Binary values cross the API as base64 text.

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

create function private.b64(v bytea)
returns text language sql immutable strict
set search_path = ''
as $$ select translate(encode(v, 'base64'), E'\n', '') $$;

create function private.unb64(v text)
returns bytea language plpgsql immutable strict
set search_path = ''
as $$
begin
  return decode(v, 'base64');
exception when others then
  raise exception 'invalid_base64' using errcode = 'PT422';
end;
$$;

-- The caller's device, active. Everything that acts "as a device" goes through this.
create function private.my_device(p_device uuid)
returns uuid language plpgsql stable
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  if not exists (select 1 from public.devices
                  where device_id = p_device and user_id = v_uid and revoked_at is null) then
    raise exception 'device_unavailable' using errcode = 'PT403';
  end if;
  return p_device;
end;
$$;

-- ---------------------------------------------------------------------------
-- Contacts: a request by exact username, accepted by the other side.
-- Two rows per pair (one per owner) so each side reads only its own.
-- ---------------------------------------------------------------------------

create table public.contacts (
  owner_id   uuid        not null references auth.users (id) on delete cascade,
  contact_id uuid        not null references auth.users (id) on delete cascade,
  state      text        not null check (state in ('pending_out', 'pending_in', 'accepted')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (owner_id, contact_id),
  check (owner_id <> contact_id)
);

alter table public.contacts enable row level security;
create policy "read own contacts" on public.contacts
  for select to authenticated using (owner_id = auth.uid());
revoke all on public.contacts from public, anon, authenticated;
grant select on public.contacts to authenticated;

create function private.are_contacts(a uuid, b uuid)
returns boolean language sql stable
set search_path = ''
as $$
  select exists (select 1 from public.contacts
                  where owner_id = a and contact_id = b and state = 'accepted')
$$;

-- May the caller address this user's devices? Accepted contacts, or themself
-- (their own other devices).
create function private.can_reach(p_user uuid)
returns boolean language sql stable
set search_path = ''
as $$
  select p_user = auth.uid() or private.are_contacts(auth.uid(), p_user)
$$;

-- Sends a request, or accepts one if they already asked. Returns the new state.
-- Errors: user_not_found (also for non-discoverable users), cannot_add_self, rate_limited.
create function public.request_contact(p_username text)
returns table (user_id uuid, username text, display_name text, state text)
language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid     uuid := private.require_user();
  v_target  public.profiles;
  v_theirs  text;
  v_state   text;
begin
  perform private.hit_rate_limit(v_uid::text, 'request_contact', 30, interval '1 hour');

  select * into v_target from public.profiles p
   where p.username = private.normalize_username(p_username) and p.discoverable;
  if not found then
    raise exception 'user_not_found' using errcode = 'PT404';
  end if;
  if v_target.user_id = v_uid then
    raise exception 'cannot_add_self' using errcode = 'PT422';
  end if;

  select c.state into v_theirs from public.contacts c
   where c.owner_id = v_target.user_id and c.contact_id = v_uid;

  if v_theirs in ('pending_out', 'accepted') then
    v_state := 'accepted';
  else
    v_state := 'pending_out';
  end if;

  insert into public.contacts (owner_id, contact_id, state) values (v_uid, v_target.user_id, v_state)
  on conflict (owner_id, contact_id) do update
    set state = case when public.contacts.state = 'accepted' then 'accepted' else excluded.state end,
        updated_at = now()
  returning public.contacts.state into v_state;

  insert into public.contacts (owner_id, contact_id, state)
  values (v_target.user_id, v_uid, case when v_state = 'accepted' then 'accepted' else 'pending_in' end)
  on conflict (owner_id, contact_id) do update
    set state = case when v_state = 'accepted' then 'accepted' else public.contacts.state end,
        updated_at = now();

  return query select v_target.user_id, v_target.username, v_target.display_name, v_state;
end;
$$;

create function public.accept_contact(p_user_id uuid)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  update public.contacts set state = 'accepted', updated_at = now()
   where owner_id = v_uid and contact_id = p_user_id and state = 'pending_in';
  if not found then
    raise exception 'request_not_found' using errcode = 'PT404';
  end if;
  update public.contacts set state = 'accepted', updated_at = now()
   where owner_id = p_user_id and contact_id = v_uid;
end;
$$;

-- Declines, cancels or removes: both sides' rows go. Undelivered mail between
-- the two is dropped as well.
create function public.remove_contact(p_user_id uuid)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  delete from public.contacts
   where (owner_id = v_uid and contact_id = p_user_id)
      or (owner_id = p_user_id and contact_id = v_uid);
  delete from public.mailbox
   where (from_user = v_uid and to_device in (select device_id from public.devices where user_id = p_user_id))
      or (from_user = p_user_id and to_device in (select device_id from public.devices where user_id = v_uid));
end;
$$;

create function public.list_contacts()
returns table (user_id uuid, username text, display_name text, state text, updated_at timestamptz)
language sql security definer stable
set search_path = ''
as $$
  select c.contact_id, p.username, p.display_name, c.state, c.updated_at
    from public.contacts c
    join public.profiles p on p.user_id = c.contact_id
   where c.owner_id = auth.uid()
   order by c.updated_at desc
$$;

-- For storage policies (they run as the caller and can't reach `private`).
create function public.is_contact(p_user uuid)
returns boolean language sql security definer stable
set search_path = ''
as $$ select private.are_contacts(auth.uid(), p_user) $$;

-- ---------------------------------------------------------------------------
-- Device keys. Each device has its own identity key; it is set once and can
-- never change (a new key means a new device, which contacts are warned about).
-- ---------------------------------------------------------------------------

alter table public.devices
  add column identity_key    bytea check (octet_length(identity_key) = 33),
  add column registration_id integer check (registration_id between 1 and 16380);

create table public.signed_prekeys (
  device_id  uuid primary key references public.devices (device_id) on delete cascade,
  key_id     integer     not null,
  public_key bytea       not null check (octet_length(public_key) = 33),
  signature  bytea       not null check (octet_length(signature) = 64),
  created_at timestamptz not null default now()
);

-- Post-quantum (ML-KEM / Kyber-1024) last-resort pre-key, required by PQXDH.
create table public.kyber_prekeys (
  device_id  uuid primary key references public.devices (device_id) on delete cascade,
  key_id     integer     not null,
  public_key bytea       not null check (octet_length(public_key) between 1000 and 2000),
  signature  bytea       not null check (octet_length(signature) = 64),
  created_at timestamptz not null default now()
);

create table public.one_time_prekeys (
  device_id  uuid    not null references public.devices (device_id) on delete cascade,
  key_id     integer not null,
  public_key bytea   not null check (octet_length(public_key) = 33),
  primary key (device_id, key_id)
);

-- Never readable directly; bundles come out of claim_prekey_bundle().
alter table public.signed_prekeys enable row level security;
alter table public.kyber_prekeys enable row level security;
alter table public.one_time_prekeys enable row level security;
revoke all on public.signed_prekeys, public.kyber_prekeys, public.one_time_prekeys from public, anon, authenticated;

-- Publishes (or tops up) this device's keys. Any part may be null.
-- p_signed / p_kyber: {"id": int, "key": b64, "sig": b64}; p_one_time: [{"id": int, "key": b64}, ...]
create function public.publish_keys(
  p_device uuid, p_identity_key text, p_registration_id integer,
  p_signed jsonb, p_kyber jsonb, p_one_time jsonb)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_device uuid := private.my_device(p_device);
  v_ik     bytea := private.unb64(p_identity_key);
  v_dev    public.devices;
begin
  perform private.hit_rate_limit(v_device::text, 'publish_keys', 30, interval '1 hour');

  select * into v_dev from public.devices where device_id = v_device for update;
  if v_ik is not null then
    if v_dev.identity_key is not null and v_dev.identity_key <> v_ik then
      raise exception 'identity_immutable' using errcode = 'PT409';
    end if;
    if p_registration_id is null then
      raise exception 'invalid_keys' using errcode = 'PT422';
    end if;
    update public.devices set identity_key = v_ik, registration_id = p_registration_id
     where device_id = v_device;
  elsif v_dev.identity_key is null then
    raise exception 'identity_missing' using errcode = 'PT409';
  end if;

  if p_signed is not null then
    insert into public.signed_prekeys (device_id, key_id, public_key, signature)
    values (v_device, (p_signed->>'id')::int, private.unb64(p_signed->>'key'), private.unb64(p_signed->>'sig'))
    on conflict (device_id) do update
      set key_id = excluded.key_id, public_key = excluded.public_key,
          signature = excluded.signature, created_at = now();
  end if;

  if p_kyber is not null then
    insert into public.kyber_prekeys (device_id, key_id, public_key, signature)
    values (v_device, (p_kyber->>'id')::int, private.unb64(p_kyber->>'key'), private.unb64(p_kyber->>'sig'))
    on conflict (device_id) do update
      set key_id = excluded.key_id, public_key = excluded.public_key,
          signature = excluded.signature, created_at = now();
  end if;

  if p_one_time is not null then
    if jsonb_array_length(p_one_time) > 200 then
      raise exception 'too_many_keys' using errcode = 'PT422';
    end if;
    insert into public.one_time_prekeys (device_id, key_id, public_key)
    select v_device, (k->>'id')::int, private.unb64(k->>'key')
      from jsonb_array_elements(p_one_time) k
    on conflict do nothing;
    if (select count(*) from public.one_time_prekeys where device_id = v_device) > 500 then
      raise exception 'too_many_keys' using errcode = 'PT422';
    end if;
  end if;
exception when invalid_text_representation or null_value_not_allowed or not_null_violation or check_violation then
  raise exception 'invalid_keys' using errcode = 'PT422';
end;
$$;

create function public.prekey_status(p_device uuid)
returns table (one_time_count integer, signed_key_id integer, signed_created_at timestamptz, kyber_key_id integer, has_identity boolean)
language plpgsql security definer stable
set search_path = ''
as $$
declare
  v_device uuid := private.my_device(p_device);
begin
  return query select
    (select count(*)::int from public.one_time_prekeys where device_id = v_device),
    (select s.key_id from public.signed_prekeys s where s.device_id = v_device),
    (select s.created_at from public.signed_prekeys s where s.device_id = v_device),
    (select k.key_id from public.kyber_prekeys k where k.device_id = v_device),
    (select d.identity_key is not null from public.devices d where d.device_id = v_device);
end;
$$;

-- Active, key-ready devices of a contact (or of the caller), with identity keys.
-- Clients compare this list with what they know to spot new or removed devices.
create function public.contact_devices(p_user_id uuid)
returns table (device_id uuid, registration_id integer, identity_key text, created_at timestamptz)
language plpgsql security definer stable
set search_path = ''
as $$
begin
  perform private.require_user();
  if not private.can_reach(p_user_id) then
    raise exception 'not_a_contact' using errcode = 'PT403';
  end if;
  return query
    select d.device_id, d.registration_id, private.b64(d.identity_key), d.created_at
      from public.devices d
     where d.user_id = p_user_id and d.revoked_at is null and d.identity_key is not null
       and exists (select 1 from public.signed_prekeys s where s.device_id = d.device_id)
       and exists (select 1 from public.kyber_prekeys k where k.device_id = d.device_id)
     order by d.created_at;
end;
$$;

-- Everything needed to start a session with one device. Pops one one-time
-- pre-key atomically (null when they've run out; the session still works).
create function public.claim_prekey_bundle(p_device_id uuid)
returns table (device_id uuid, user_id uuid, registration_id integer, identity_key text,
               signed_key_id integer, signed_key text, signed_sig text,
               kyber_key_id integer, kyber_key text, kyber_sig text,
               prekey_id integer, prekey text)
language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
  v_dev public.devices;
  v_otk public.one_time_prekeys;
begin
  perform private.hit_rate_limit(v_uid::text, 'claim_prekey_bundle', 200, interval '1 hour');

  select * into v_dev from public.devices d
   where d.device_id = p_device_id and d.revoked_at is null and d.identity_key is not null;
  if not found or not private.can_reach(v_dev.user_id) then
    raise exception 'device_unavailable' using errcode = 'PT403';
  end if;

  delete from public.one_time_prekeys o
   where (o.device_id, o.key_id) = (
     select o2.device_id, o2.key_id from public.one_time_prekeys o2
      where o2.device_id = p_device_id
      order by o2.key_id limit 1 for update skip locked)
  returning * into v_otk;

  return query
    select v_dev.device_id, v_dev.user_id, v_dev.registration_id, private.b64(v_dev.identity_key),
           s.key_id, private.b64(s.public_key), private.b64(s.signature),
           k.key_id, private.b64(k.public_key), private.b64(k.signature),
           v_otk.key_id, private.b64(v_otk.public_key)
      from public.signed_prekeys s
      join public.kyber_prekeys k on k.device_id = s.device_id
     where s.device_id = p_device_id;
end;
$$;

-- ---------------------------------------------------------------------------
-- Mailbox: one opaque Signal ciphertext per recipient device. Deleted when
-- the recipient acknowledges it, or after 30 days.
-- ---------------------------------------------------------------------------

create table public.mailbox (
  id          bigint generated always as identity primary key,
  to_device   uuid        not null references public.devices (device_id) on delete cascade,
  from_user   uuid        not null references auth.users (id) on delete cascade,
  from_device uuid        not null,
  type        smallint    not null check (type in (2, 3)),  -- Signal: 2 = message, 3 = pre-key message
  envelope    bytea       not null check (octet_length(envelope) between 1 and 65536),
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default now() + interval '30 days'
);

create index mailbox_to_device_idx on public.mailbox (to_device, id);
create index mailbox_expires_idx on public.mailbox (expires_at);

alter table public.mailbox enable row level security;
-- Read access exists only so Realtime can notify a device of new mail; the
-- row carries nothing the recipient's own device couldn't fetch anyway.
create policy "read own mail" on public.mailbox
  for select to authenticated
  using (to_device in (select d.device_id from public.devices d where d.user_id = auth.uid()));
revoke all on public.mailbox from public, anon, authenticated;
grant select on public.mailbox to authenticated;

-- p_messages: [{"to": device uuid, "type": 2|3, "body": b64}, ...]  (one per recipient device)
create function public.send_messages(p_from_device uuid, p_messages jsonb)
returns integer language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid    uuid := private.require_user();
  v_from   uuid := private.my_device(p_from_device);
  v_msg    jsonb;
  v_to     public.devices;
  v_count  integer := 0;
begin
  if jsonb_typeof(p_messages) <> 'array' or jsonb_array_length(p_messages) not between 1 and 20 then
    raise exception 'invalid_messages' using errcode = 'PT422';
  end if;
  perform private.hit_rate_limit(v_uid::text, 'send_messages', 600, interval '1 hour');

  for v_msg in select * from jsonb_array_elements(p_messages) loop
    select * into v_to from public.devices d
     where d.device_id = (v_msg->>'to')::uuid and d.revoked_at is null;
    if not found or v_to.device_id = v_from or not private.can_reach(v_to.user_id) then
      raise exception 'device_unavailable' using errcode = 'PT403', hint = coalesce(v_msg->>'to', '');
    end if;
    insert into public.mailbox (to_device, from_user, from_device, type, envelope)
    values (v_to.device_id, v_uid, v_from, (v_msg->>'type')::smallint, private.unb64(v_msg->>'body'));
    v_count := v_count + 1;
  end loop;
  return v_count;
exception when invalid_text_representation or check_violation or not_null_violation then
  raise exception 'invalid_messages' using errcode = 'PT422';
end;
$$;

create function public.fetch_messages(p_device uuid, p_limit integer default 100)
returns table (id bigint, from_user uuid, from_device uuid, type smallint, body text, created_at timestamptz)
language plpgsql security definer
set search_path = ''
as $$
declare
  v_device uuid := private.my_device(p_device);
begin
  delete from public.mailbox m where m.expires_at < now();
  return query
    select m.id, m.from_user, m.from_device, m.type, private.b64(m.envelope), m.created_at
      from public.mailbox m
     where m.to_device = v_device
     order by m.id
     limit least(greatest(p_limit, 1), 500);
end;
$$;

create function public.ack_messages(p_device uuid, p_ids bigint[])
returns integer language plpgsql security definer
set search_path = ''
as $$
declare
  v_device uuid := private.my_device(p_device);
  v_count  integer;
begin
  delete from public.mailbox where to_device = v_device and id = any (p_ids);
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

-- Revoking a device also drops its keys and undelivered mail.
create function private.on_device_revoked()
returns trigger language plpgsql security definer
set search_path = ''
as $$
begin
  if new.revoked_at is not null and old.revoked_at is null then
    delete from public.one_time_prekeys where device_id = new.device_id;
    delete from public.signed_prekeys where device_id = new.device_id;
    delete from public.kyber_prekeys where device_id = new.device_id;
    delete from public.mailbox where to_device = new.device_id;
  end if;
  return new;
end;
$$;

create trigger devices_revoked after update of revoked_at on public.devices
  for each row execute function private.on_device_revoked();

-- Realtime notifications for new mail (only present on a real Supabase database).
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table public.mailbox;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Attachments: encrypted blobs in a private bucket, one folder per sender.
-- The decryption key travels only inside the Signal message.
-- ---------------------------------------------------------------------------

do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    insert into storage.buckets (id, name, public, file_size_limit)
    values ('attachments', 'attachments', false, 104857600)
    on conflict (id) do nothing;

    execute $p$
      create policy "upload own attachments" on storage.objects for insert to authenticated
      with check (bucket_id = 'attachments' and (storage.foldername(name))[1] = auth.uid()::text)
    $p$;
    execute $p$
      create policy "read own or contacts' attachments" on storage.objects for select to authenticated
      using (bucket_id = 'attachments' and (
        (storage.foldername(name))[1] = auth.uid()::text
        or public.is_contact(((storage.foldername(name))[1])::uuid)))
    $p$;
    execute $p$
      create policy "delete own attachments" on storage.objects for delete to authenticated
      using (bucket_id = 'attachments' and (storage.foldername(name))[1] = auth.uid()::text)
    $p$;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Function privileges
-- ---------------------------------------------------------------------------

revoke execute on all functions in schema private from public, anon, authenticated;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.request_contact(text)', 'public.accept_contact(uuid)', 'public.remove_contact(uuid)',
    'public.list_contacts()', 'public.is_contact(uuid)',
    'public.publish_keys(uuid, text, integer, jsonb, jsonb, jsonb)', 'public.prekey_status(uuid)',
    'public.contact_devices(uuid)', 'public.claim_prekey_bundle(uuid)',
    'public.send_messages(uuid, jsonb)', 'public.fetch_messages(uuid, integer)',
    'public.ack_messages(uuid, bigint[])'
  ] loop
    execute format('revoke execute on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end;
$$;
