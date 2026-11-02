-- Phase 4: 1:1 calls. The server only records that a call exists between two
-- contacts (who, when, how long) so it can hand out room access to exactly
-- those two. The invite itself and the media encryption key travel inside
-- Signal messages; LiveKit only ever relays frames it can't decrypt.

create table public.calls (
  call_id    uuid primary key default gen_random_uuid(),
  caller_id  uuid        not null references auth.users (id) on delete cascade,
  callee_id  uuid        not null references auth.users (id) on delete cascade,
  video      boolean     not null default true,
  created_at timestamptz not null default now(),
  answered_at timestamptz,
  ended_at   timestamptz,
  check (caller_id <> callee_id)
);

create index calls_caller_idx on public.calls (caller_id, created_at desc);
create index calls_callee_idx on public.calls (callee_id, created_at desc);

alter table public.calls enable row level security;
create policy "read own calls" on public.calls
  for select to authenticated using (auth.uid() in (caller_id, callee_id));
revoke all on public.calls from public, anon, authenticated;
grant select on public.calls to authenticated;

-- Starts a call to an accepted contact. Errors: not_a_contact, rate_limited.
create function public.create_call(p_callee uuid, p_video boolean default true)
returns uuid language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
  v_id  uuid;
begin
  perform private.hit_rate_limit(v_uid::text, 'create_call', 60, interval '1 hour');
  if not private.are_contacts(v_uid, p_callee) then
    raise exception 'not_a_contact' using errcode = 'PT403';
  end if;
  insert into public.calls (caller_id, callee_id, video) values (v_uid, p_callee, coalesce(p_video, true))
  returning call_id into v_id;
  return v_id;
end;
$$;

-- Marks a call answered or ended (either participant). Times only, nothing else.
create function public.update_call(p_call uuid, p_state text)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  if p_state not in ('answered', 'ended') then
    raise exception 'invalid_state' using errcode = 'PT422';
  end if;
  update public.calls
     set answered_at = case when p_state = 'answered' then coalesce(answered_at, now()) else answered_at end,
         ended_at    = case when p_state = 'ended' then coalesce(ended_at, now()) else ended_at end
   where call_id = p_call and v_uid in (caller_id, callee_id);
  if not found then
    raise exception 'call_not_found' using errcode = 'PT404';
  end if;
end;
$$;

-- Used by the livekit-token function (service role) to check membership.
create function public.call_member(p_call uuid, p_user uuid)
returns boolean language sql security definer stable
set search_path = ''
as $$
  select exists (
    select 1 from public.calls c
     where c.call_id = p_call and p_user in (c.caller_id, c.callee_id)
       and c.ended_at is null and c.created_at > now() - interval '12 hours'
       and private.are_contacts(c.caller_id, c.callee_id))
$$;

revoke execute on all functions in schema private from public, anon, authenticated;
revoke execute on function public.create_call(uuid, boolean) from public, anon;
revoke execute on function public.update_call(uuid, text) from public, anon;
revoke execute on function public.call_member(uuid, uuid) from public, anon, authenticated;
grant execute on function public.create_call(uuid, boolean) to authenticated;
grant execute on function public.update_call(uuid, text) to authenticated;
grant execute on function public.call_member(uuid, uuid) to service_role;
