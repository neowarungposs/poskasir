-- D28 rev: the registered contact is fixed for the owner. Only SUPER_ADMIN changes it (Kasir Admin), logged.
revoke execute on function public.update_contact(text, boolean) from public, anon, authenticated;

create or replace function public.admin_update_contact(p_tenant_id uuid, p_phone_wa text, p_wa_opt_in boolean)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  t public.tenants;
  v_phone text := nullif(regexp_replace(coalesce(p_phone_wa, ''), '\D', '', 'g'), '');
  v_opt boolean;
begin
  m := public.require_role(array['SUPER_ADMIN']);
  select * into t from public.tenants where id = p_tenant_id for update;
  if t.id is null then raise exception 'TENANT_NOT_FOUND' using errcode = 'P0001'; end if;
  if v_phone is not null and length(v_phone) not between 9 and 15 then
    raise exception 'INVALID_PHONE' using errcode = 'P0001';
  end if;
  v_opt := coalesce(p_wa_opt_in, t.wa_opt_in);
  if v_phone is not distinct from t.phone_wa and v_opt = t.wa_opt_in then return; end if;

  update public.tenants set phone_wa = v_phone, wa_opt_in = v_opt where id = t.id;

  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id, payload)
  values (t.id, m.user_id, 'UPDATE_CONTACT', 'tenants', t.id::text,
          jsonb_build_object('old_phone', t.phone_wa, 'new_phone', v_phone,
                             'old_opt_in', t.wa_opt_in, 'new_opt_in', v_opt, 'by', 'SUPER_ADMIN'));
end $$;

revoke execute on function public.admin_update_contact(uuid, text, boolean) from public, anon;
grant execute on function public.admin_update_contact(uuid, text, boolean) to authenticated;
