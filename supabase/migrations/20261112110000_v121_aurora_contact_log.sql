-- v1.2.1: Aurora Glass becomes the default theme (D20 rev), contact changes are logged (D28).

alter table public.tenants drop constraint if exists tenants_theme_check;
alter table public.tenants add constraint tenants_theme_check check (theme in ('AURORA','BENTO','NEON','MIDNIGHT'));
alter table public.tenants alter column theme set default 'AURORA';
update public.tenants set theme = 'AURORA' where theme = 'BENTO';

create or replace function public.update_contact(p_phone_wa text, p_wa_opt_in boolean)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  t public.tenants;
  v_phone text := nullif(regexp_replace(coalesce(p_phone_wa, ''), '\D', '', 'g'), '');
  v_opt boolean;
begin
  m := public.require_role(array['TENANT_ADMIN']);
  select * into t from public.tenants where id = m.tenant_id for update;
  v_opt := coalesce(p_wa_opt_in, t.wa_opt_in);
  if v_phone is not distinct from t.phone_wa and v_opt = t.wa_opt_in then return; end if;

  update public.tenants set phone_wa = v_phone, wa_opt_in = v_opt where id = t.id;

  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id, payload)
  values (t.id, m.user_id, 'UPDATE_CONTACT', 'tenants', t.id::text,
          jsonb_build_object('old_phone', t.phone_wa, 'new_phone', v_phone,
                             'old_opt_in', t.wa_opt_in, 'new_opt_in', v_opt));
end $$;

revoke execute on function public.update_contact(text, boolean) from public, anon;
grant execute on function public.update_contact(text, boolean) to authenticated;
