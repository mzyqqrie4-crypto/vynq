-- Contact profiles: encrypted profile pictures, and deleting a chat's history.
--
-- Profile pictures are encrypted on the phone with the owner's profile key, which
-- travels only inside Signal messages to their contacts. The server stores an
-- opaque file in a private bucket readable by the owner and their contacts, and
-- its path on the profile. It can't see the picture.

alter table public.profiles add column avatar_path text
  constraint avatar_path_shape check (avatar_path is null or (char_length(avatar_path) between 3 and 120 and avatar_path !~ '[[:cntrl:]]'));

-- Set or clear the caller's profile picture. The path must be in their own folder.
create function public.set_avatar(p_path text)
returns void language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
begin
  perform private.hit_rate_limit(v_uid::text, 'set_avatar', 30, interval '1 hour');
  if p_path is not null and split_part(p_path, '/', 1) <> v_uid::text then
    raise exception 'invalid_avatar_path';
  end if;
  update public.profiles set avatar_path = p_path where user_id = v_uid;
end;
$$;

create function public.my_avatar()
returns text language sql security definer stable
set search_path = ''
as $$ select avatar_path from public.profiles where user_id = auth.uid() $$;

-- Contacts now include the profile picture path (only accepted contacts see it).
drop function public.list_contacts();
create function public.list_contacts()
returns table (user_id uuid, username text, display_name text, state text, updated_at timestamptz, avatar_path text)
language sql security definer stable
set search_path = ''
as $$
  select c.contact_id, p.username, p.display_name, c.state, c.updated_at,
         case when c.state = 'accepted' then p.avatar_path end
    from public.contacts c
    join public.profiles p on p.user_id = c.contact_id
   where c.owner_id = auth.uid()
   order by c.updated_at desc
$$;

-- Delete some of the caller's history records (deleting a chat). At most 500 ids.
create function public.delete_history(p_ids text[])
returns integer language plpgsql security definer
set search_path = ''
as $$
declare
  v_uid uuid := private.require_user();
  v_n   integer;
begin
  if p_ids is null or cardinality(p_ids) > 500 then
    raise exception 'invalid_ids';
  end if;
  perform private.hit_rate_limit(v_uid::text, 'delete_history', 200, interval '1 hour');
  delete from public.history where user_id = v_uid and record_id = any (p_ids);
  get diagnostics v_n = row_count;
  return v_n;
end;
$$;

-- Encrypted profile pictures: one folder per user, readable by the owner and their contacts.
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    insert into storage.buckets (id, name, public, file_size_limit)
    values ('avatars', 'avatars', false, 2097152)
    on conflict (id) do nothing;

    execute $p$
      create policy "upload own avatar" on storage.objects for insert to authenticated
      with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text)
    $p$;
    execute $p$
      create policy "read own or contacts' avatars" on storage.objects for select to authenticated
      using (bucket_id = 'avatars' and (
        (storage.foldername(name))[1] = auth.uid()::text
        or public.is_contact(((storage.foldername(name))[1])::uuid)))
    $p$;
    execute $p$
      create policy "delete own avatars" on storage.objects for delete to authenticated
      using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text)
    $p$;
  end if;
end;
$$;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.set_avatar(text)', 'public.my_avatar()', 'public.list_contacts()', 'public.delete_history(text[])'
  ] loop
    execute format('revoke execute on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end;
$$;
