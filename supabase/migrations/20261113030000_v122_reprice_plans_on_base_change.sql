-- D31: when the monthly base price changes, every active plan is re-priced with its own discount.
-- Old plan rows are retired (not deleted) so late transfers at the old price still match for 180 days.
create or replace function public._reprice_active_plans()
returns int language plpgsql security definer set search_path = '' as $$
declare
  v_base bigint;
  r public.plans;
  v_normal bigint;
  v_price bigint;
  v_n int := 0;
begin
  select monthly_base_price into v_base from public.system_settings where id = 1;
  perform pg_advisory_xact_lock(hashtext('plans'));
  for r in select * from public.plans where is_active order by months loop
    v_normal := v_base * r.months;
    v_price := (floor(v_normal * (1 - r.discount_pct / 100.0) / 1000) * 1000)::bigint;
    continue when v_price = r.price and v_normal = r.normal_price;
    if v_price <= 0 then raise exception 'INVALID_PRICE' using errcode = 'P0001'; end if;
    if exists (select 1 from public.plans p where p.price = v_price and p.months <> r.months
               and (p.is_active or p.retired_at > now() - interval '180 days')) then
      raise exception 'PRICE_COLLISION' using errcode = 'P0001',
        hint = format('Harga paket %s bulan (%s) bentrok dengan paket lain, ubah diskonnya sedikit', r.months, v_price);
    end if;
    update public.plans set is_active = false, retired_at = now() where id = r.id;
    insert into public.plans(months, normal_price, discount_pct, price, badge, promo_ends_at)
    values (r.months, v_normal, r.discount_pct, v_price, r.badge, r.promo_ends_at);
    v_n := v_n + 1;
  end loop;
  if v_n > 0 then
    insert into public.audit_logs(action, entity, entity_id, payload)
    values ('REPRICE_PLANS', 'plans', null, jsonb_build_object('base', v_base, 'changed', v_n));
  end if;
  return v_n;
end $$;

revoke execute on function public._reprice_active_plans() from public, anon, authenticated;

create or replace function public.trg_reprice_plans()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.monthly_base_price is distinct from old.monthly_base_price then
    perform public._reprice_active_plans();
  end if;
  return new;
end $$;

drop trigger if exists reprice_plans_on_base_change on public.system_settings;
create trigger reprice_plans_on_base_change
  after update of monthly_base_price on public.system_settings
  for each row execute function public.trg_reprice_plans();

-- Apply to the price already saved (50.000).
select public._reprice_active_plans();
