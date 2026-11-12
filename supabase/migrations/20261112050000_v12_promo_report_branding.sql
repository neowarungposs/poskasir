-- =====================================================================
-- POS SaaS v1.2 — HPP/margin, per-outlet products, receipt settings,
-- tenant branding + theme, promotions, order details, report RPC
-- =====================================================================

-- Products: cost price (HPP)
alter table public.products add column if not exists cost_price bigint not null default 0 check (cost_price >= 0);

-- Per-outlet availability and price override
create table if not exists public.product_outlets (
  product_id uuid not null references public.products(id) on delete cascade,
  outlet_id uuid not null references public.outlets(id) on delete cascade,
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  is_available boolean not null default true,
  price_override bigint check (price_override is null or price_override >= 0),
  updated_at timestamptz not null default now(),
  primary key (product_id, outlet_id)
);
create index if not exists idx_product_outlets_tenant_upd on public.product_outlets(tenant_id, updated_at);
create index if not exists idx_product_outlets_outlet on public.product_outlets(outlet_id);
create trigger trg_touch_product_outlets before update on public.product_outlets for each row execute function public.touch_updated_at();
create trigger trg_product_outlets_tenant before insert or update on public.product_outlets
  for each row execute function public.set_tenant_id_from_jwt();
alter table public.product_outlets enable row level security;
create policy po_read on public.product_outlets for select to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));
create policy po_admin_insert on public.product_outlets for insert to authenticated
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
create policy po_admin_update on public.product_outlets for update to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN')
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
create policy po_admin_delete on public.product_outlets for delete to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
revoke all on public.product_outlets from anon;

-- Receipt settings per outlet
alter table public.outlets
  add column if not exists phone text,
  add column if not exists receipt_header text,
  add column if not exists receipt_footer text,
  add column if not exists receipt_show_logo boolean not null default true;

-- Tenant branding + theme (written through RPC; tenants is RPC-only)
alter table public.tenants
  add column if not exists logo_path text,
  add column if not exists login_bg_path text,
  add column if not exists theme text not null default 'BENTO' check (theme in ('BENTO','NEON','MIDNIGHT'));

create or replace function public.update_branding(
  p_logo_path text default null,
  p_login_bg_path text default null,
  p_theme text default null,
  p_clear_logo boolean default false,
  p_clear_bg boolean default false)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
begin
  m := public.require_role(array['TENANT_ADMIN']);
  if p_logo_path is not null and split_part(p_logo_path, '/', 1) <> m.tenant_id::text then
    raise exception 'INVALID_PATH' using errcode = 'P0001';
  end if;
  if p_login_bg_path is not null and split_part(p_login_bg_path, '/', 1) <> m.tenant_id::text then
    raise exception 'INVALID_PATH' using errcode = 'P0001';
  end if;
  update public.tenants set
    logo_path = case when p_clear_logo then null else coalesce(p_logo_path, logo_path) end,
    login_bg_path = case when p_clear_bg then null else coalesce(p_login_bg_path, login_bg_path) end,
    theme = coalesce(p_theme, theme)
  where id = m.tenant_id;
end $$;

-- Tenant contact (WA for renewal reminders)
create or replace function public.update_contact(p_phone_wa text, p_wa_opt_in boolean)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.memberships;
begin
  m := public.require_role(array['TENANT_ADMIN']);
  update public.tenants set phone_wa = nullif(regexp_replace(coalesce(p_phone_wa, ''), '\D', '', 'g'), ''),
                            wa_opt_in = coalesce(p_wa_opt_in, wa_opt_in)
  where id = m.tenant_id;
end $$;

-- Order details: order type, table, subtotal, discount, promo; item cost + note
alter table public.sales_orders
  add column if not exists order_type text not null default 'DINE_IN' check (order_type in ('DINE_IN','TAKEAWAY')),
  add column if not exists table_no text,
  add column if not exists subtotal bigint,
  add column if not exists discount bigint not null default 0 check (discount >= 0),
  add column if not exists promo_summary text;
alter table public.sales_order_items
  add column if not exists unit_cost bigint not null default 0,
  add column if not exists note text;

-- Promotions (evaluated offline on the device)
create table if not exists public.promotions (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  name text not null,
  type text not null check (type in ('BUNDLE','BXGY','PERCENT','AMOUNT','MIN_SPEND')),
  config jsonb not null default '{}'::jsonb,
  outlet_ids uuid[],                         -- null = all outlets
  starts_at timestamptz,
  ends_at timestamptz,
  days int[],                                -- ISO 1=Mon..7=Sun, null = every day
  start_minute int check (start_minute between 0 and 1439),
  end_minute int check (end_minute between 0 and 1440),
  is_active boolean not null default true,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_promotions_tenant_upd on public.promotions(tenant_id, updated_at);
create trigger trg_touch_promotions before update on public.promotions for each row execute function public.touch_updated_at();
create trigger trg_promotions_tenant before insert or update on public.promotions
  for each row execute function public.set_tenant_id_from_jwt();
alter table public.promotions enable row level security;
create policy promo_read on public.promotions for select to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));
create policy promo_admin_insert on public.promotions for insert to authenticated
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
create policy promo_admin_update on public.promotions for update to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN')
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
revoke all on public.promotions from anon;
revoke delete on public.promotions from authenticated;

-- Sync v2: accepts the new order/item fields (backward compatible)
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
                                    payment_method, total, status, sync_flag, created_at,
                                    order_type, table_no, subtotal, discount, promo_summary)
    values ((o ->> 'id')::uuid, t.id, v_outlet, o ->> 'device_id', o ->> 'invoice_no', m.user_id,
            o ->> 'payment_method', (o ->> 'total')::bigint, 'PAID', v_flag, v_created,
            coalesce(o ->> 'order_type', 'DINE_IN'), nullif(o ->> 'table_no', ''),
            coalesce((o ->> 'subtotal')::bigint, (o ->> 'total')::bigint),
            coalesce((o ->> 'discount')::bigint, 0), nullif(o ->> 'promo_summary', ''))
    on conflict (id) do nothing;
    get diagnostics v_rows = row_count;

    if v_rows = 0 then
      v_dupes := v_dupes + 1;
      continue;
    end if;

    v_inserted := v_inserted + 1;
    if v_flag = 'FLAGGED' then v_flagged := v_flagged + 1; end if;

    insert into public.sales_order_items(id, order_id, tenant_id, product_id, product_name,
                                         unit_price, qty, subtotal, unit_cost, note)
    select (i ->> 'id')::uuid, (o ->> 'id')::uuid, t.id,
           (select p.id from public.products p
             where p.id = nullif(i ->> 'product_id', '')::uuid and p.tenant_id = t.id),
           i ->> 'product_name', (i ->> 'unit_price')::bigint, (i ->> 'qty')::numeric,
           (i ->> 'subtotal')::bigint, coalesce((i ->> 'unit_cost')::bigint, 0), nullif(i ->> 'note', '')
    from jsonb_array_elements(coalesce(o -> 'items', '[]'::jsonb)) i
    on conflict (id) do nothing;
  end loop;

  return jsonb_build_object('inserted', v_inserted, 'duplicates', v_dupes,
                            'flagged', v_flagged, 'server_now', now());
end $$;

-- Detailed report for TENANT_ADMIN (one call, aggregated server-side)
create or replace function public.report_summary(p_from timestamptz, p_to timestamptz, p_outlet uuid default null)
returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  m public.memberships;
  r jsonb;
begin
  m := public.require_role(array['TENANT_ADMIN']);
  with o as (
    select so.* from public.sales_orders so
    where so.tenant_id = m.tenant_id and so.created_at >= p_from and so.created_at < p_to
      and (p_outlet is null or so.outlet_id = p_outlet)
  ),
  paid as (select * from o where status = 'PAID'),
  it as (
    select i.*, paid.outlet_id, paid.cashier_id from public.sales_order_items i join paid on paid.id = i.order_id
  )
  select jsonb_build_object(
    'omzet', (select coalesce(sum(total), 0) from paid),
    'subtotal', (select coalesce(sum(coalesce(subtotal, total)), 0) from paid),
    'discount', (select coalesce(sum(discount), 0) from paid),
    'trx', (select count(*) from paid),
    'void_count', (select count(*) from o where status = 'VOIDED'),
    'void_total', (select coalesce(sum(total), 0) from o where status = 'VOIDED'),
    'hpp', (select coalesce(sum(unit_cost * qty), 0) from it),
    'by_outlet', (select coalesce(jsonb_agg(x order by x.omzet desc), '[]') from (
        select ol.name, count(p.*) as trx, coalesce(sum(p.total), 0) as omzet,
               coalesce((select sum(it.unit_cost * it.qty) from it where it.outlet_id = ol.id), 0) as hpp
        from public.outlets ol left join paid p on p.outlet_id = ol.id
        where ol.tenant_id = m.tenant_id and (p_outlet is null or ol.id = p_outlet)
        group by ol.id, ol.name) x),
    'by_product', (select coalesce(jsonb_agg(x order by x.omzet desc), '[]') from (
        select product_name as name, sum(qty) as qty, sum(subtotal) as omzet, sum(unit_cost * qty) as hpp
        from it group by product_name order by sum(subtotal) desc limit 100) x),
    'by_cashier', (select coalesce(jsonb_agg(x order by x.omzet desc), '[]') from (
        select coalesce(mb.email, 'tidak diketahui') as name, count(*) as trx, sum(p.total) as omzet
        from paid p left join public.memberships mb on mb.user_id = p.cashier_id
        group by mb.email) x),
    'by_method', (select coalesce(jsonb_agg(x order by x.omzet desc), '[]') from (
        select payment_method as name, count(*) as trx, sum(total) as omzet from paid group by payment_method) x),
    'by_hour', (select coalesce(jsonb_agg(x order by x.hour), '[]') from (
        select extract(hour from created_at at time zone 'Asia/Jakarta')::int as hour, count(*) as trx, sum(total) as omzet
        from paid group by 1) x),
    'transactions', (select coalesce(jsonb_agg(x order by x.created_at desc), '[]') from (
        select so.invoice_no, so.created_at, ol.name as outlet, coalesce(mb.email, '-') as cashier, so.payment_method,
               coalesce(so.subtotal, so.total) as subtotal, so.discount, so.total, so.status, so.promo_summary
        from o so join public.outlets ol on ol.id = so.outlet_id
        left join public.memberships mb on mb.user_id = so.cashier_id
        order by so.created_at desc limit 5000) x)
  ) into r;
  return r;
end $$;

revoke execute on function public.update_branding(text, text, text, boolean, boolean) from public, anon;
revoke execute on function public.update_contact(text, boolean) from public, anon;
revoke execute on function public.report_summary(timestamptz, timestamptz, uuid) from public, anon;
grant execute on function public.update_branding(text, text, text, boolean, boolean) to authenticated;
grant execute on function public.update_contact(text, boolean) to authenticated;
grant execute on function public.report_summary(timestamptz, timestamptz, uuid) to authenticated;
grant execute on function public.sync_push_orders(jsonb) to authenticated;
