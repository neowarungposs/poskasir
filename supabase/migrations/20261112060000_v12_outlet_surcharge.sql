-- v1.2.0: configurable surcharge per extra outlet (D27).
-- Price per plan = plan.price + extra_outlets * extra_outlet_monthly_price * months * (1 - discount_pct/100),
-- rounded down to Rp1.000 so the last 3 digits stay free for pay_code.

alter table public.system_settings
  add column if not exists extra_outlet_monthly_price bigint not null default 0
    check (extra_outlet_monthly_price >= 0 and extra_outlet_monthly_price % 1000 = 0);

create or replace function public.tenant_extra_outlets(p_tenant uuid)
returns int language sql stable security definer set search_path = '' as $$
  select greatest(count(*)::int - 1, 0) from public.outlets where tenant_id = p_tenant and deleted_at is null;
$$;

create or replace function public.tenant_plan_price(p_tenant uuid, p_plan uuid)
returns bigint language sql stable security definer set search_path = '' as $$
  select p.price + (floor(public.tenant_extra_outlets(p_tenant) * s.extra_outlet_monthly_price * p.months
                     * (100 - coalesce(p.discount_pct, 0)) / 100 / 1000) * 1000)::bigint
  from public.plans p cross join public.system_settings s
  where p.id = p_plan and s.id = 1;
$$;

create or replace function public.tenant_months_price(p_tenant uuid, p_months int)
returns bigint language sql stable security definer set search_path = '' as $$
  select (s.monthly_base_price + public.tenant_extra_outlets(p_tenant) * s.extra_outlet_monthly_price) * p_months
  from public.system_settings s where s.id = 1;
$$;

-- Prices for the signed-in tenant (payment web page).
create or replace function public.my_plan_prices()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
  m public.memberships;
  s public.system_settings;
begin
  m := public.require_role(array['TENANT_ADMIN']);
  select * into s from public.system_settings where id = 1;
  return jsonb_build_object(
    'extra_outlets', public.tenant_extra_outlets(m.tenant_id),
    'extra_outlet_monthly_price', s.extra_outlet_monthly_price,
    'plans', coalesce((select jsonb_agg(jsonb_build_object(
        'plan_id', p.id, 'months', p.months, 'base_price', p.price,
        'price', public.tenant_plan_price(m.tenant_id, p.id)) order by p.months)
      from public.plans p where p.is_active), '[]'::jsonb));
end $$;

create or replace function public.approve_payment(p_incoming_id uuid, p_tenant_id uuid, p_plan_id uuid default null,
  p_months integer default null, p_claim_id uuid default null, p_note text default null)
returns timestamptz language plpgsql security definer set search_path = '' as $function$
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
    v_price := public.tenant_plan_price(t.id, pl.id);
  elsif p_months between 1 and 24 then
    v_months := p_months;
    v_price := public.tenant_months_price(t.id, p_months);
  else
    raise exception 'PLAN_OR_MONTHS_REQUIRED' using errcode = 'P0001';
  end if;

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
          jsonb_build_object('amount', ip.amount, 'months', v_months, 'price', v_price,
                             'extra_outlets', public.tenant_extra_outlets(t.id), 'new_paid_until', v_new));

  return v_new;
end $function$;

create or replace function public.submit_payment(p_plan_id uuid default null, p_sender_name text default null,
  p_proof_path text default null, p_proof_sha256 text default null, p_transfer_at timestamptz default null)
returns uuid language plpgsql security definer set search_path = '' as $function$
declare
  m public.memberships;
  t public.tenants;
  pl public.plans;
  v_price bigint;
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
    v_price := public.tenant_plan_price(t.id, pl.id);
  end if;

  if p_proof_path is not null and split_part(p_proof_path, '/', 1) <> t.id::text then
    raise exception 'INVALID_PROOF_PATH' using errcode = 'P0001';
  end if;

  begin
    insert into public.payment_confirmations(tenant_id, plan_id, plan_price_snapshot, expected_amount,
      sender_name, proof_path, proof_sha256, transfer_at, submitted_by)
    values (t.id, pl.id, v_price,
            case when pl.id is null then null else v_price + t.pay_code end,
            nullif(trim(p_sender_name), ''), p_proof_path, p_proof_sha256, p_transfer_at, m.user_id)
    returning id into v_id;
  exception when unique_violation then
    raise exception 'DUPLICATE_PROOF' using errcode = 'P0001';
  end;

  return v_id;
end $function$;

create or replace function public.build_reminder_queue()
returns integer language plpgsql security definer set search_path = '' as $function$
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
    select p.id from public.plans p where p.is_active order by p.months desc limit 1
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
      public.tenant_plan_price(c.id, b.id) + c.pay_code as amount
    from cand c
    left join best b on true
  )
  insert into public.wa_reminder_queue(tenant_id, kind, period_end, plan_suggestion_id, amount)
  select tenant_id, kind, period_end, plan_id, amount from r where kind is not null
  on conflict (tenant_id, kind, period_end) do nothing;
  get diagnostics v_count = row_count;

  update public.reminder_settings set last_queue_run_date = v_today where id = 1;
  return v_count;
end $function$;

create or replace function public.decode_amount(p_amount bigint)
returns table(tenant_id uuid, customer_code text, business_name text, lifecycle text, pay_code integer,
  base_amount bigint, plan_id uuid, plan_months integer, plan_price bigint, plan_is_active boolean, match_kind text)
language plpgsql stable security definer set search_path = '' as $function$
#variable_conflict use_column
begin
  perform public.require_role(array['SUPER_ADMIN']);
  return query
  with d as (
    select (p_amount % 1000)::int as code, (p_amount - (p_amount % 1000))::bigint as base
  )
  select tn.id, tn.customer_code, tn.business_name, tn.lifecycle, d.code, d.base,
         pl.id, pl.months, pl.tprice, pl.is_active,
         case
           when pl.id is not null then 'PLAN_PRICE'
           when d.base > 0 and tn.id is not null
                and d.base % (s.monthly_base_price + public.tenant_extra_outlets(tn.id) * s.extra_outlet_monthly_price) = 0
             then 'MULTIPLE_OF_MONTHLY'
           when d.base > 0 and d.base % s.monthly_base_price = 0 then 'MULTIPLE_OF_MONTHLY'
           else 'NO_PLAN_MATCH'
         end
  from d
  cross join public.system_settings s
  left join public.tenants tn on tn.pay_code = d.code
  left join lateral (
    select p.*, case when tn.id is null then p.price else public.tenant_plan_price(tn.id, p.id) end as tprice
    from public.plans p
    where (p.is_active or p.retired_at > now() - interval '180 days')
      and (case when tn.id is null then p.price else public.tenant_plan_price(tn.id, p.id) end = d.base
           or p.price = d.base)
    order by (case when tn.id is null then p.price else public.tenant_plan_price(tn.id, p.id) end = d.base) desc,
             p.is_active desc, p.created_at desc
    limit 1
  ) pl on true
  where s.id = 1;
end $function$;

revoke execute on function public.tenant_extra_outlets(uuid) from public, anon, authenticated;
revoke execute on function public.tenant_plan_price(uuid, uuid) from public, anon, authenticated;
revoke execute on function public.tenant_months_price(uuid, int) from public, anon, authenticated;
revoke execute on function public.my_plan_prices() from public, anon;
grant execute on function public.my_plan_prices() to authenticated;
