-- =====================================================================
-- POS SaaS v1.1 — cashier emails, server push via Edge Function "notify"
-- DB events -> pg_net -> /functions/v1/notify (authenticated by a Vault secret)
-- =====================================================================

-- Cashier list needs a readable email (auth.users is not exposed).
alter table public.memberships add column if not exists email text;
update public.memberships m set email = u.email from auth.users u where u.id = m.user_id and m.email is null;

-- Shared secret between the database and the notify function.
select vault.create_secret(encode(extensions.gen_random_bytes(32), 'hex'), 'webhook_secret',
                           'DB -> notify Edge Function shared secret')
where not exists (select 1 from vault.secrets where name = 'webhook_secret');

create or replace function public.notify_edge(p_payload jsonb)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_secret text;
begin
  select decrypted_secret into v_secret from vault.decrypted_secrets where name = 'webhook_secret';
  if v_secret is null then return; end if;
  perform net.http_post(
    url := 'https://hktdjfixchzrltlwoxnb.supabase.co/functions/v1/notify',
    body := p_payload,
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', v_secret),
    timeout_milliseconds := 10000
  );
end $$;

create or replace function public.trg_notify_claim() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform public.notify_edge(jsonb_build_object('type', 'NEW_CLAIM', 'id', new.id));
  return new;
end $$;

create or replace function public.trg_notify_event() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.kind in ('EXTEND', 'REJECT') then
    perform public.notify_edge(jsonb_build_object('type', 'SUBSCRIPTION_EVENT', 'id', new.id));
  end if;
  return new;
end $$;

create or replace function public.trg_notify_void() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    perform public.notify_edge(jsonb_build_object('type', 'VOID_REQUEST', 'id', new.id));
  elsif new.status is distinct from old.status and new.status <> 'PENDING' then
    perform public.notify_edge(jsonb_build_object('type', 'VOID_DECIDED', 'id', new.id));
  end if;
  return new;
end $$;

create trigger trg_claims_notify after insert on public.payment_confirmations
  for each row execute function public.trg_notify_claim();
create trigger trg_events_notify after insert on public.subscription_events
  for each row execute function public.trg_notify_event();
create trigger trg_voids_notify after insert or update of status on public.void_requests
  for each row execute function public.trg_notify_void();

-- Owner membership keeps an email too (cashier email is set by manage_cashier).
create or replace function public.register_new_tenant(
  p_business_name text,
  p_outlet_name text default null,
  p_phone_wa text default null,
  p_wa_opt_in boolean default false)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_email text;
  v_code int;
  v_customer text;
  v_tenant uuid;
  v_outlet uuid;
begin
  if v_uid is null then
    raise exception 'NOT_AUTHENTICATED' using errcode = '42501';
  end if;
  if exists (select 1 from public.memberships where user_id = v_uid) then
    raise exception 'ALREADY_REGISTERED' using errcode = 'P0001';
  end if;

  select u.email into v_email from auth.users u where u.id = v_uid;

  perform pg_advisory_xact_lock(hashtext('pay_code_alloc'));
  select c into v_code
  from generate_series(1, 999) c
  where not exists (select 1 from public.pay_codes p where p.code = c)
  order by random()
  limit 1;
  if v_code is null then
    raise exception 'PAY_CODE_EXHAUSTED' using errcode = 'P0001';
  end if;
  insert into public.pay_codes(code) values (v_code);

  loop
    v_customer := 'WRG-' || upper(substr(md5(gen_random_uuid()::text), 1, 4));
    exit when not exists (select 1 from public.tenants t where t.customer_code = v_customer);
  end loop;

  insert into public.tenants(business_name, owner_user_id, customer_code, email, phone_wa,
                             wa_opt_in, pay_code, trial_ends_at)
  values (trim(p_business_name), v_uid, v_customer, v_email, nullif(trim(p_phone_wa), ''),
          coalesce(p_wa_opt_in, false), v_code, now() + interval '30 days')
  returning id into v_tenant;

  update public.pay_codes set tenant_id = v_tenant where code = v_code;

  insert into public.outlets(tenant_id, code, name, is_main)
  values (v_tenant, 'PUSAT', coalesce(nullif(trim(p_outlet_name), ''), trim(p_business_name)), true)
  returning id into v_outlet;

  insert into public.memberships(user_id, tenant_id, outlet_id, role, email)
  values (v_uid, v_tenant, v_outlet, 'TENANT_ADMIN', v_email);

  insert into public.subscription_events(tenant_id, kind, actor_id)
  values (v_tenant, 'TRIAL_START', v_uid);
  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id)
  values (v_tenant, v_uid, 'REGISTER', 'tenants', v_tenant::text);

  return v_tenant;
end $$;

revoke execute on function public.notify_edge(jsonb) from public, anon, authenticated;
revoke execute on function public.trg_notify_claim() from public, anon, authenticated;
revoke execute on function public.trg_notify_event() from public, anon, authenticated;
revoke execute on function public.trg_notify_void() from public, anon, authenticated;
grant execute on function public.register_new_tenant(text, text, text, boolean) to authenticated;

-- Daily push: tenant expiry reminders + admin summary at 09:05 WIB (02:05 UTC).
select cron.schedule('daily-push', '5 2 * * *', $$select public.notify_edge('{"type":"DAILY"}'::jsonb)$$);

-- Default URLs of the static pages (GitHub Pages).
update public.system_settings
set payment_page_url = coalesce(payment_page_url, 'https://neowarungposs.github.io/poskasir/'),
    deletion_page_url = coalesce(deletion_page_url, 'https://neowarungposs.github.io/poskasir/hapus-akun.html')
where id = 1;

-- The notify Edge Function validates the caller's secret against Vault (service_role only).
create or replace function public.check_webhook_secret(p_secret text)
returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from vault.decrypted_secrets where name = 'webhook_secret' and decrypted_secret = p_secret)
$$;
revoke execute on function public.check_webhook_secret(text) from public, anon, authenticated;
grant execute on function public.check_webhook_secret(text) to service_role;
