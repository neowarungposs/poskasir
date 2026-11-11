-- =====================================================================
-- POS SaaS v2 — Step 1c: RPCs, scheduled jobs, function grants
-- RPCs re-check the caller's role from public.memberships (authoritative),
-- never from client input.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Internal helpers
-- ---------------------------------------------------------------------
create or replace function public.require_role(p_roles text[])
returns public.memberships
language plpgsql stable security definer set search_path = '' as $$
declare
  m public.memberships;
begin
  select * into m from public.memberships where user_id = auth.uid();
  if m.user_id is null or not (m.role = any (p_roles)) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  return m;
end $$;

create or replace function public.tenant_access_until(t public.tenants)
returns timestamptz language sql immutable set search_path = '' as $$
  select greatest(t.trial_ends_at, coalesce(t.paid_until, '-infinity'::timestamptz))
$$;

-- ---------------------------------------------------------------------
-- Onboarding: trial 30 days, permanent pay_code, main outlet
-- ---------------------------------------------------------------------
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

  insert into public.memberships(user_id, tenant_id, outlet_id, role)
  values (v_uid, v_tenant, v_outlet, 'TENANT_ADMIN');

  insert into public.subscription_events(tenant_id, kind, actor_id)
  values (v_tenant, 'TRIAL_START', v_uid);
  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id)
  values (v_tenant, v_uid, 'REGISTER', 'tenants', v_tenant::text);

  return v_tenant; -- client must refresh session to receive new JWT claims
end $$;

-- ---------------------------------------------------------------------
-- Entitlement (D8): status is derived, server time only
-- ---------------------------------------------------------------------
create or replace function public.get_entitlement()
returns table(server_now timestamptz, tenant_id uuid, customer_code text, pay_code int,
              trial_ends_at timestamptz, paid_until timestamptz, access_until timestamptz,
              grace_until timestamptz, status text, max_offline_days int)
language plpgsql stable security definer set search_path = '' as $$
#variable_conflict use_column
declare
  m public.memberships;
  t public.tenants;
  s public.system_settings;
  v_access timestamptz;
  v_grace timestamptz;
begin
  m := public.require_role(array['TENANT_ADMIN','BRANCH_CASHIER']);
  select * into t from public.tenants x where x.id = m.tenant_id;
  select * into s from public.system_settings x where x.id = 1;
  v_access := public.tenant_access_until(t);
  v_grace := v_access + make_interval(days => s.grace_days);
  return query select
    now(), t.id, t.customer_code, t.pay_code, t.trial_ends_at, t.paid_until, v_access, v_grace,
    case
      when t.paid_until is null and now() <= t.trial_ends_at then 'TRIAL'
      when now() <= v_access then 'ACTIVE'
      when now() <= v_grace then 'GRACE'
      else 'EXPIRED'
    end,
    s.max_offline_days;
end $$;

-- ---------------------------------------------------------------------
-- Plans & discounts (D12) — versioned, price unique across 180 days
-- ---------------------------------------------------------------------
create or replace function public.upsert_plan(
  p_months int,
  p_discount_pct numeric,
  p_badge text default null,
  p_promo_ends_at timestamptz default null,
  p_is_active boolean default true)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  v_base bigint;
  v_normal bigint;
  v_price bigint;
  v_id uuid;
begin
  m := public.require_role(array['SUPER_ADMIN']);
  if p_months is null or p_months not between 1 and 24 then
    raise exception 'INVALID_MONTHS' using errcode = 'P0001';
  end if;
  if p_discount_pct is null or p_discount_pct not between 0 and 90 then
    raise exception 'INVALID_DISCOUNT' using errcode = 'P0001';
  end if;

  select monthly_base_price into v_base from public.system_settings where id = 1;
  v_normal := v_base * p_months;
  v_price := (floor(v_normal * (1 - p_discount_pct / 100.0) / 1000) * 1000)::bigint;
  if v_price <= 0 then
    raise exception 'INVALID_PRICE' using errcode = 'P0001';
  end if;

  perform pg_advisory_xact_lock(hashtext('plans'));

  if p_is_active and exists (
    select 1 from public.plans p
    where p.price = v_price and p.months <> p_months
      and (p.is_active or p.retired_at > now() - interval '180 days')) then
    raise exception 'PRICE_COLLISION' using errcode = 'P0001',
      hint = 'Harga bentrok dengan paket lain, ubah diskon sedikit';
  end if;

  update public.plans set is_active = false, retired_at = now()
  where months = p_months and is_active;

  if p_is_active then
    insert into public.plans(months, normal_price, discount_pct, price, badge, promo_ends_at)
    values (p_months, v_normal, p_discount_pct, v_price, p_badge, p_promo_ends_at)
    returning id into v_id;
  end if;

  insert into public.audit_logs(actor_id, action, entity, entity_id, payload)
  values (m.user_id, 'UPSERT_PLAN', 'plans', v_id::text,
          jsonb_build_object('months', p_months, 'discount_pct', p_discount_pct,
                             'price', v_price, 'active', p_is_active));
  return v_id;
end $$;

-- ---------------------------------------------------------------------
-- Payment claim from the payment page (D1, D7). Proof & sender optional.
-- ---------------------------------------------------------------------
create or replace function public.submit_payment(
  p_plan_id uuid default null,
  p_sender_name text default null,
  p_proof_path text default null,
  p_proof_sha256 text default null,
  p_transfer_at timestamptz default null)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  t public.tenants;
  pl public.plans;
  v_id uuid;
begin
  m := public.require_role(array['TENANT_ADMIN']);
  select * into t from public.tenants where id = m.tenant_id;

  if p_plan_id is not null then
    select * into pl from public.plans p
    where p.id = p_plan_id and (p.is_active or p.retired_at > now() - interval '180 days');
    if pl.id is null then
      raise exception 'PLAN_NOT_FOUND' using errcode = 'P0001';
    end if;
  end if;

  if p_proof_path is not null and split_part(p_proof_path, '/', 1) <> t.id::text then
    raise exception 'INVALID_PROOF_PATH' using errcode = 'P0001';
  end if;

  begin
    insert into public.payment_confirmations(tenant_id, plan_id, plan_price_snapshot, expected_amount,
      sender_name, proof_path, proof_sha256, transfer_at, submitted_by)
    values (t.id, pl.id, pl.price,
            case when pl.id is null then null else pl.price + t.pay_code end,
            nullif(trim(p_sender_name), ''), p_proof_path, p_proof_sha256, p_transfer_at, m.user_id)
    returning id into v_id;
  exception when unique_violation then
    raise exception 'DUPLICATE_PROOF' using errcode = 'P0001';
  end;

  return v_id;
end $$;

-- ---------------------------------------------------------------------
-- Money ledger (source of truth) — SUPER_ADMIN only
-- ---------------------------------------------------------------------
create or replace function public.record_incoming_payment(
  p_channel text,
  p_amount bigint,
  p_received_at timestamptz,
  p_sender_name text default null,
  p_bank_ref text default null,
  p_note text default null)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  v_tenant uuid;
  v_id uuid;
begin
  m := public.require_role(array['SUPER_ADMIN']);
  select t.id into v_tenant from public.tenants t where t.pay_code = (p_amount % 1000)::int;

  insert into public.incoming_payments(channel, amount, received_at, sender_name, bank_ref,
                                       decoded_tenant_id, note, recorded_by)
  values (p_channel, p_amount, p_received_at, nullif(trim(p_sender_name), ''),
          nullif(trim(p_bank_ref), ''), v_tenant, p_note, m.user_id)
  returning id into v_id;

  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id, payload)
  values (v_tenant, m.user_id, 'RECORD_INCOMING', 'incoming_payments', v_id::text,
          jsonb_build_object('amount', p_amount, 'channel', p_channel));
  return v_id;
exception when unique_violation then
  raise exception 'DUPLICATE_BANK_REF' using errcode = 'P0001';
end $$;

-- Decode an amount: last 3 digits = pay_code, rest = plan price. SUGGESTION ONLY.
create or replace function public.decode_amount(p_amount bigint)
returns table(tenant_id uuid, customer_code text, business_name text, lifecycle text,
              pay_code int, base_amount bigint, plan_id uuid, plan_months int,
              plan_price bigint, plan_is_active boolean, match_kind text)
language plpgsql stable security definer set search_path = '' as $$
#variable_conflict use_column
begin
  perform public.require_role(array['SUPER_ADMIN']);
  return query
  with d as (
    select (p_amount % 1000)::int as code, (p_amount - (p_amount % 1000))::bigint as base
  )
  select tn.id, tn.customer_code, tn.business_name, tn.lifecycle, d.code, d.base,
         pl.id, pl.months, pl.price, pl.is_active,
         case
           when pl.id is not null then 'PLAN_PRICE'
           when d.base > 0 and d.base % s.monthly_base_price = 0 then 'MULTIPLE_OF_MONTHLY'
           else 'NO_PLAN_MATCH'
         end
  from d
  cross join public.system_settings s
  left join public.tenants tn on tn.pay_code = d.code
  left join lateral (
    select p.* from public.plans p
    where p.price = d.base and (p.is_active or p.retired_at > now() - interval '180 days')
    order by p.is_active desc, p.created_at desc
    limit 1
  ) pl on true
  where s.id = 1;
end $$;

-- ---------------------------------------------------------------------
-- Approve: requires a ledger row (D7). One transaction.
-- ---------------------------------------------------------------------
create or replace function public.approve_payment(
  p_incoming_id uuid,
  p_tenant_id uuid,
  p_plan_id uuid default null,
  p_months int default null,
  p_claim_id uuid default null,
  p_note text default null)
returns timestamptz
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  ip public.incoming_payments;
  t public.tenants;
  pl public.plans;
  cl public.payment_confirmations;
  v_months int;
  v_price bigint;
  v_old timestamptz;
  v_new timestamptz;
  v_remainder bigint;
begin
  m := public.require_role(array['SUPER_ADMIN']);

  select * into ip from public.incoming_payments where id = p_incoming_id for update;
  if ip.id is null then raise exception 'INCOMING_NOT_FOUND' using errcode = 'P0001'; end if;
  if ip.status <> 'UNMATCHED' then raise exception 'INCOMING_ALREADY_USED' using errcode = 'P0001'; end if;

  select * into t from public.tenants where id = p_tenant_id for update;
  if t.id is null then raise exception 'TENANT_NOT_FOUND' using errcode = 'P0001'; end if;

  if p_plan_id is not null then
    select * into pl from public.plans where id = p_plan_id;
    if pl.id is null then raise exception 'PLAN_NOT_FOUND' using errcode = 'P0001'; end if;
    v_months := pl.months;
    v_price := pl.price;
  elsif p_months between 1 and 24 then
    v_months := p_months;
    select monthly_base_price * p_months into v_price from public.system_settings where id = 1;
  else
    raise exception 'PLAN_OR_MONTHS_REQUIRED' using errcode = 'P0001';
  end if;

  -- money must cover at least the plan price (pay_code may be missing)
  if ip.amount < v_price then
    raise exception 'AMOUNT_TOO_LOW' using errcode = 'P0001';
  end if;

  if p_claim_id is not null then
    select * into cl from public.payment_confirmations where id = p_claim_id for update;
    if cl.id is null or cl.tenant_id <> t.id then raise exception 'CLAIM_MISMATCH' using errcode = 'P0001'; end if;
    if cl.status <> 'PENDING' then raise exception 'CLAIM_NOT_PENDING' using errcode = 'P0001'; end if;
  end if;

  v_old := t.paid_until;
  v_new := greatest(now(), public.tenant_access_until(t)) + make_interval(months => v_months);

  if t.lifecycle = 'CHURNED' then
    insert into public.subscription_events(tenant_id, kind, actor_id, note)
    values (t.id, 'REVIVE', m.user_id, 'Revived by payment');
  end if;

  update public.tenants
  set paid_until = v_new, lifecycle = 'LIVE', churned_at = null, churn_reason = null
  where id = t.id;

  update public.incoming_payments
  set status = 'MATCHED', matched_tenant_id = t.id, claim_id = p_claim_id,
      note = coalesce(p_note, note)
  where id = ip.id;

  if p_claim_id is not null then
    update public.payment_confirmations
    set status = 'APPROVED', reviewed_by = m.user_id, reviewed_at = now()
    where id = p_claim_id;
  end if;

  v_remainder := ip.amount - (v_price + t.pay_code);
  if v_remainder > 0 then
    insert into public.credit_ledger(tenant_id, amount, reason, incoming_payment_id, created_by)
    values (t.id, v_remainder, 'OVERPAYMENT', ip.id, m.user_id);
  end if;

  insert into public.subscription_events(tenant_id, kind, months, old_paid_until, new_paid_until,
                                         incoming_payment_id, claim_id, actor_id, note)
  values (t.id, 'EXTEND', v_months, v_old, v_new, ip.id, p_claim_id, m.user_id, p_note);

  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id, payload)
  values (t.id, m.user_id, 'APPROVE_PAYMENT', 'incoming_payments', ip.id::text,
          jsonb_build_object('amount', ip.amount, 'months', v_months, 'new_paid_until', v_new));

  return v_new;
end $$;

create or replace function public.reject_payment(p_claim_id uuid, p_reason text)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  cl public.payment_confirmations;
begin
  m := public.require_role(array['SUPER_ADMIN']);
  select * into cl from public.payment_confirmations where id = p_claim_id for update;
  if cl.id is null then raise exception 'CLAIM_NOT_FOUND' using errcode = 'P0001'; end if;
  if cl.status <> 'PENDING' then raise exception 'CLAIM_NOT_PENDING' using errcode = 'P0001'; end if;

  update public.payment_confirmations
  set status = 'REJECTED', rejection_reason = p_reason, reviewed_by = m.user_id, reviewed_at = now()
  where id = p_claim_id;

  insert into public.subscription_events(tenant_id, kind, claim_id, actor_id, note)
  values (cl.tenant_id, 'REJECT', cl.id, m.user_id, p_reason);
  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id, payload)
  values (cl.tenant_id, m.user_id, 'REJECT_PAYMENT', 'payment_confirmations', cl.id::text,
          jsonb_build_object('reason', p_reason));
end $$;

-- ---------------------------------------------------------------------
-- Tenant lifecycle (D11)
-- ---------------------------------------------------------------------
create or replace function public.mark_churned(p_tenant_id uuid, p_reason text)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
begin
  m := public.require_role(array['SUPER_ADMIN']);
  update public.tenants
  set lifecycle = 'CHURNED', churned_at = now(), churn_reason = coalesce(p_reason, 'TIDAK_LANJUT')
  where id = p_tenant_id and lifecycle = 'LIVE';
  if not found then raise exception 'TENANT_NOT_LIVE' using errcode = 'P0001'; end if;

  update public.payment_confirmations set status = 'CANCELLED'
  where tenant_id = p_tenant_id and status = 'PENDING';
  update public.wa_reminder_queue set status = 'SKIPPED'
  where tenant_id = p_tenant_id and status = 'PENDING';

  insert into public.subscription_events(tenant_id, kind, actor_id, note)
  values (p_tenant_id, 'CHURN', m.user_id, p_reason);
end $$;

-- cron only
create or replace function public.auto_churn()
returns int
language plpgsql security definer set search_path = '' as $$
declare
  v_days int;
  v_grace int;
  v_ids uuid[];
begin
  select auto_churn_after_days into v_days from public.reminder_settings where id = 1;
  select grace_days into v_grace from public.system_settings where id = 1;

  with c as (
    update public.tenants t
    set lifecycle = 'CHURNED', churned_at = now(), churn_reason = 'AUTO_EXPIRED'
    where t.lifecycle = 'LIVE'
      and public.tenant_access_until(t) + make_interval(days => v_grace + v_days) < now()
    returning t.id
  )
  select array_agg(id) into v_ids from c;

  if v_ids is null then return 0; end if;

  update public.payment_confirmations set status = 'CANCELLED'
  where tenant_id = any (v_ids) and status = 'PENDING';
  update public.wa_reminder_queue set status = 'SKIPPED'
  where tenant_id = any (v_ids) and status = 'PENDING';
  insert into public.subscription_events(tenant_id, kind, note)
  select unnest(v_ids), 'CHURN', 'AUTO_EXPIRED';

  return array_length(v_ids, 1);
end $$;

-- cron only: end promos, restore normal price
create or replace function public.expire_promos()
returns int
language plpgsql security definer set search_path = '' as $$
declare
  p public.plans;
  n int := 0;
begin
  for p in
    select * from public.plans
    where is_active and promo_ends_at is not null and promo_ends_at <= now()
    for update
  loop
    update public.plans set is_active = false, retired_at = now() where id = p.id;
    begin
      insert into public.plans(months, normal_price, discount_pct, price, badge)
      values (p.months, p.normal_price, 0, p.normal_price, p.badge);
    exception when unique_violation then
      null; -- price collides with another active plan; admin must re-set it
    end;
    n := n + 1;
  end loop;
  return n;
end $$;

-- cron only: WA reminder queue for the admin app (D10)
create or replace function public.build_reminder_queue()
returns int
language plpgsql security definer set search_path = '' as $$
declare
  rs public.reminder_settings;
  s public.system_settings;
  v_today date := (now() at time zone 'Asia/Jakarta')::date;
  v_count int := 0;
begin
  select * into rs from public.reminder_settings where id = 1;
  select * into s from public.system_settings where id = 1;

  if not rs.enabled then return 0; end if;
  if extract(hour from now() at time zone 'Asia/Jakarta')::int <> rs.run_hour then return 0; end if;
  if rs.last_queue_run_date = v_today then return 0; end if;

  with best as (
    select p.id, p.price from public.plans p where p.is_active order by p.months desc limit 1
  ),
  cand as (
    select t.id, t.pay_code, public.tenant_access_until(t) as acc
    from public.tenants t
    where t.lifecycle = 'LIVE' and t.wa_opt_in and t.phone_wa is not null
  ),
  r as (
    select c.id as tenant_id,
      case
        when ((c.acc at time zone 'Asia/Jakarta')::date - v_today) = any (rs.days_before)
          then 'H-' || ((c.acc at time zone 'Asia/Jakarta')::date - v_today)
        when rs.remind_on_grace and now() > c.acc
             and now() <= c.acc + make_interval(days => s.grace_days)
          then 'GRACE-' || v_today::text
        else null
      end as kind,
      c.acc as period_end,
      b.id as plan_id,
      b.price + c.pay_code as amount
    from cand c
    left join best b on true
  )
  insert into public.wa_reminder_queue(tenant_id, kind, period_end, plan_suggestion_id, amount)
  select tenant_id, kind, period_end, plan_id, amount from r where kind is not null
  on conflict (tenant_id, kind, period_end) do nothing;
  get diagnostics v_count = row_count;

  update public.reminder_settings set last_queue_run_date = v_today where id = 1;
  return v_count;
end $$;

-- ---------------------------------------------------------------------
-- Offline sync (D9): idempotent, never rejects historical sales
-- p_orders: [{id, outlet_id, device_id, invoice_no, payment_method, total, created_at,
--             items:[{id, product_id, product_name, unit_price, qty, subtotal}]}]
-- ---------------------------------------------------------------------
create or replace function public.sync_push_orders(p_orders jsonb)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  t public.tenants;
  v_grace timestamptz;
  o jsonb;
  v_outlet uuid;
  v_created timestamptz;
  v_flag text;
  v_rows int;
  v_inserted int := 0;
  v_dupes int := 0;
  v_flagged int := 0;
begin
  m := public.require_role(array['TENANT_ADMIN','BRANCH_CASHIER']);
  if jsonb_typeof(p_orders) <> 'array' or jsonb_array_length(p_orders) > 500 then
    raise exception 'INVALID_BATCH' using errcode = 'P0001';
  end if;

  select * into t from public.tenants where id = m.tenant_id;
  select public.tenant_access_until(t) + make_interval(days => s.grace_days)
    into v_grace from public.system_settings s where s.id = 1;

  for o in select value from jsonb_array_elements(p_orders) loop
    v_outlet := (o ->> 'outlet_id')::uuid;
    if m.role = 'BRANCH_CASHIER' and v_outlet <> m.outlet_id then
      raise exception 'OUTLET_FORBIDDEN' using errcode = '42501';
    end if;
    if not exists (select 1 from public.outlets x where x.id = v_outlet and x.tenant_id = t.id) then
      raise exception 'OUTLET_NOT_FOUND' using errcode = 'P0001';
    end if;

    v_created := (o ->> 'created_at')::timestamptz;
    v_flag := case
      when v_created <= v_grace and v_created <= now() + interval '10 minutes' then 'OK'
      else 'FLAGGED'
    end;

    insert into public.sales_orders(id, tenant_id, outlet_id, device_id, invoice_no, cashier_id,
                                    payment_method, total, status, sync_flag, created_at)
    values ((o ->> 'id')::uuid, t.id, v_outlet, o ->> 'device_id', o ->> 'invoice_no', m.user_id,
            o ->> 'payment_method', (o ->> 'total')::bigint, 'PAID', v_flag, v_created)
    on conflict (id) do nothing;
    get diagnostics v_rows = row_count;

    if v_rows = 0 then
      v_dupes := v_dupes + 1;
      continue;
    end if;

    v_inserted := v_inserted + 1;
    if v_flag = 'FLAGGED' then v_flagged := v_flagged + 1; end if;

    insert into public.sales_order_items(id, order_id, tenant_id, product_id, product_name,
                                         unit_price, qty, subtotal)
    select (i ->> 'id')::uuid, (o ->> 'id')::uuid, t.id,
           (select p.id from public.products p
             where p.id = nullif(i ->> 'product_id', '')::uuid and p.tenant_id = t.id),
           i ->> 'product_name', (i ->> 'unit_price')::bigint, (i ->> 'qty')::numeric,
           (i ->> 'subtotal')::bigint
    from jsonb_array_elements(coalesce(o -> 'items', '[]'::jsonb)) i
    on conflict (id) do nothing;
  end loop;

  return jsonb_build_object('inserted', v_inserted, 'duplicates', v_dupes,
                            'flagged', v_flagged, 'server_now', now());
end $$;

-- ---------------------------------------------------------------------
-- Voids
-- ---------------------------------------------------------------------
create or replace function public.request_void(p_order_id uuid, p_reason text)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  o public.sales_orders;
  v_id uuid;
begin
  m := public.require_role(array['TENANT_ADMIN','BRANCH_CASHIER']);
  select * into o from public.sales_orders where id = p_order_id and tenant_id = m.tenant_id;
  if o.id is null then raise exception 'ORDER_NOT_FOUND' using errcode = 'P0001'; end if;
  if m.role = 'BRANCH_CASHIER' and o.outlet_id <> m.outlet_id then
    raise exception 'OUTLET_FORBIDDEN' using errcode = '42501';
  end if;
  if o.status = 'VOIDED' then raise exception 'ALREADY_VOIDED' using errcode = 'P0001'; end if;

  insert into public.void_requests(tenant_id, order_id, requested_by, reason)
  values (m.tenant_id, o.id, m.user_id, p_reason)
  returning id into v_id;
  return v_id;
exception when unique_violation then
  raise exception 'VOID_ALREADY_PENDING' using errcode = 'P0001';
end $$;

create or replace function public.decide_void(p_void_id uuid, p_approve boolean)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
  v public.void_requests;
begin
  m := public.require_role(array['TENANT_ADMIN']);
  select * into v from public.void_requests
  where id = p_void_id and tenant_id = m.tenant_id for update;
  if v.id is null then raise exception 'VOID_NOT_FOUND' using errcode = 'P0001'; end if;
  if v.status <> 'PENDING' then raise exception 'VOID_NOT_PENDING' using errcode = 'P0001'; end if;

  update public.void_requests
  set status = case when p_approve then 'APPROVED' else 'REJECTED' end,
      decided_by = m.user_id, decided_at = now()
  where id = v.id;

  if p_approve then
    update public.sales_orders set status = 'VOIDED' where id = v.order_id;
  end if;

  insert into public.audit_logs(tenant_id, actor_id, action, entity, entity_id)
  values (m.tenant_id, m.user_id, case when p_approve then 'VOID_APPROVE' else 'VOID_REJECT' end,
          'void_requests', v.id::text);
end $$;

-- ---------------------------------------------------------------------
-- Function grants: nothing for anon; only listed RPCs for authenticated
-- ---------------------------------------------------------------------
revoke execute on all functions in schema public from public, anon, authenticated;

grant execute on function public.jwt_tenant_id() to authenticated;
grant execute on function public.jwt_outlet_id() to authenticated;
grant execute on function public.jwt_app_role() to authenticated;
grant execute on function public.is_super_admin() to authenticated;
grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;

grant execute on function public.register_new_tenant(text, text, text, boolean) to authenticated;
grant execute on function public.get_entitlement() to authenticated;
grant execute on function public.upsert_plan(int, numeric, text, timestamptz, boolean) to authenticated;
grant execute on function public.submit_payment(uuid, text, text, text, timestamptz) to authenticated;
grant execute on function public.record_incoming_payment(text, bigint, timestamptz, text, text, text) to authenticated;
grant execute on function public.decode_amount(bigint) to authenticated;
grant execute on function public.approve_payment(uuid, uuid, uuid, int, uuid, text) to authenticated;
grant execute on function public.reject_payment(uuid, text) to authenticated;
grant execute on function public.mark_churned(uuid, text) to authenticated;
grant execute on function public.sync_push_orders(jsonb) to authenticated;
grant execute on function public.request_void(uuid, text) to authenticated;
grant execute on function public.decide_void(uuid, boolean) to authenticated;

-- new functions must not be executable by default
alter default privileges in schema public revoke execute on functions from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- Scheduled jobs (UTC). 17:10 UTC = 00:10 WIB.
-- ---------------------------------------------------------------------
select cron.schedule('build-reminder-queue', '0 * * * *', $$select public.build_reminder_queue()$$);
select cron.schedule('auto-churn', '10 17 * * *', $$select public.auto_churn()$$);
select cron.schedule('expire-promos', '15 17 * * *', $$select public.expire_promos()$$);
