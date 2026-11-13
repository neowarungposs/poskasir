-- v1.2.4: HPP per payment method so the report shows gross profit + margin per method (D32).
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
    select i.*, paid.outlet_id, paid.cashier_id, paid.payment_method from public.sales_order_items i join paid on paid.id = i.order_id
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
        select p.payment_method as name, count(*) as trx, sum(p.total) as omzet,
               coalesce((select sum(it.unit_cost * it.qty) from it where it.payment_method = p.payment_method), 0) as hpp
        from paid p group by p.payment_method) x),
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
