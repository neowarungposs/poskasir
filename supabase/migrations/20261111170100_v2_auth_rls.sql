-- =====================================================================
-- POS SaaS v2 — Step 1b: JWT claims hook, helpers, RLS, grants
-- After applying: Dashboard > Authentication > Hooks >
--   "Customize Access Token (JWT) Claims" -> public.custom_access_token_hook
-- JWT custom claims: app_role, tenant_id, outlet_id  (never "role": that is the Postgres role)
-- =====================================================================

-- ---------------------------------------------------------------------
-- JWT helpers (used by RLS; fast, read claims only)
-- ---------------------------------------------------------------------
create or replace function public.jwt_tenant_id() returns uuid
language sql stable set search_path = '' as $$
  select nullif(auth.jwt() ->> 'tenant_id', '')::uuid
$$;

create or replace function public.jwt_outlet_id() returns uuid
language sql stable set search_path = '' as $$
  select nullif(auth.jwt() ->> 'outlet_id', '')::uuid
$$;

create or replace function public.jwt_app_role() returns text
language sql stable set search_path = '' as $$
  select coalesce(auth.jwt() ->> 'app_role', '')
$$;

create or replace function public.is_super_admin() returns boolean
language sql stable set search_path = '' as $$
  select public.jwt_app_role() = 'SUPER_ADMIN'
$$;

-- ---------------------------------------------------------------------
-- Custom Access Token Hook
-- ---------------------------------------------------------------------
create or replace function public.custom_access_token_hook(event jsonb)
returns jsonb language plpgsql stable set search_path = '' as $$
declare
  m public.memberships;
  claims jsonb;
begin
  select * into m from public.memberships where user_id = (event ->> 'user_id')::uuid;
  claims := coalesce(event -> 'claims', '{}'::jsonb);
  if m.user_id is not null then
    claims := claims || jsonb_build_object(
      'app_role', m.role,
      'tenant_id', m.tenant_id,
      'outlet_id', m.outlet_id);
  else
    claims := claims - 'app_role' - 'tenant_id' - 'outlet_id';
  end if;
  return jsonb_set(event, '{claims}', claims);
end $$;

grant usage on schema public to supabase_auth_admin;
grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;
grant select on table public.memberships to supabase_auth_admin;

-- ---------------------------------------------------------------------
-- tenant_id is taken from the JWT on direct client inserts (D5)
-- RPCs run as SECURITY DEFINER (current_user = owner) and set it themselves.
-- ---------------------------------------------------------------------
create or replace function public.set_tenant_id_from_jwt()
returns trigger language plpgsql set search_path = '' as $$
begin
  if current_user = 'authenticated' and not public.is_super_admin() then
    new.tenant_id := public.jwt_tenant_id();
  end if;
  return new;
end $$;

create trigger trg_outlets_tenant before insert or update on public.outlets
  for each row execute function public.set_tenant_id_from_jwt();
create trigger trg_products_tenant before insert or update on public.products
  for each row execute function public.set_tenant_id_from_jwt();

-- ---------------------------------------------------------------------
-- Enable RLS everywhere
-- ---------------------------------------------------------------------
alter table public.system_settings       enable row level security;
alter table public.pay_codes             enable row level security;
alter table public.tenants               enable row level security;
alter table public.outlets               enable row level security;
alter table public.memberships           enable row level security;
alter table public.products              enable row level security;
alter table public.sales_orders          enable row level security;
alter table public.sales_order_items     enable row level security;
alter table public.void_requests         enable row level security;
alter table public.audit_logs            enable row level security;
alter table public.fcm_tokens            enable row level security;
alter table public.plans                 enable row level security;
alter table public.payment_confirmations enable row level security;
alter table public.incoming_payments     enable row level security;
alter table public.credit_ledger         enable row level security;
alter table public.subscription_events   enable row level security;
alter table public.reminder_settings     enable row level security;
alter table public.wa_reminder_queue     enable row level security;
alter table public.reminder_log          enable row level security;

-- ---------------------------------------------------------------------
-- Table grants: anon gets nothing; transactional tables are RPC-only
-- ---------------------------------------------------------------------
revoke all on all tables in schema public from anon;

revoke insert, delete on public.system_settings, public.reminder_settings from authenticated;
revoke insert, update, delete on
  public.pay_codes, public.tenants, public.memberships,
  public.sales_orders, public.sales_order_items, public.void_requests,
  public.audit_logs, public.plans, public.payment_confirmations,
  public.incoming_payments, public.credit_ledger, public.subscription_events,
  public.reminder_log
from authenticated;
revoke insert, delete on public.wa_reminder_queue from authenticated;

-- ---------------------------------------------------------------------
-- Policies
-- ---------------------------------------------------------------------
create policy auth_admin_read_memberships on public.memberships
  for select to supabase_auth_admin using (true);

create policy ss_read on public.system_settings
  for select to authenticated using (true);
create policy ss_admin_update on public.system_settings
  for update to authenticated
  using ((select public.is_super_admin())) with check ((select public.is_super_admin()));

create policy pay_codes_admin_read on public.pay_codes
  for select to authenticated using ((select public.is_super_admin()));

create policy tenants_read on public.tenants
  for select to authenticated
  using (id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));

create policy memberships_read on public.memberships
  for select to authenticated
  using (
    user_id = (select auth.uid())
    or (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN')
    or (select public.is_super_admin())
  );

-- outlets: whole tenant reads; TENANT_ADMIN writes
create policy outlets_read on public.outlets
  for select to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));
create policy outlets_admin_insert on public.outlets
  for insert to authenticated
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
create policy outlets_admin_update on public.outlets
  for update to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN')
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');

-- products: whole tenant reads; TENANT_ADMIN writes (hard delete = purge)
create policy products_read on public.products
  for select to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));
create policy products_admin_insert on public.products
  for insert to authenticated
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
create policy products_admin_update on public.products
  for update to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN')
  with check (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');
create policy products_admin_delete on public.products
  for delete to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN');

-- sales: TENANT_ADMIN sees tenant; cashier sees own outlet. Writes via RPC only.
create policy orders_read on public.sales_orders
  for select to authenticated
  using (
    (tenant_id = (select public.jwt_tenant_id())
      and ((select public.jwt_app_role()) = 'TENANT_ADMIN' or outlet_id = (select public.jwt_outlet_id())))
    or (select public.is_super_admin())
  );
create policy items_read on public.sales_order_items
  for select to authenticated
  using (exists (select 1 from public.sales_orders o where o.id = order_id));
create policy voids_read on public.void_requests
  for select to authenticated
  using (exists (select 1 from public.sales_orders o where o.id = order_id));

create policy audit_read on public.audit_logs
  for select to authenticated
  using (
    (tenant_id = (select public.jwt_tenant_id()) and (select public.jwt_app_role()) = 'TENANT_ADMIN')
    or (select public.is_super_admin())
  );

-- fcm tokens: each user manages own rows
create policy fcm_own_select on public.fcm_tokens
  for select to authenticated using (user_id = (select auth.uid()));
create policy fcm_own_insert on public.fcm_tokens
  for insert to authenticated with check (user_id = (select auth.uid()));
create policy fcm_own_update on public.fcm_tokens
  for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy fcm_own_delete on public.fcm_tokens
  for delete to authenticated using (user_id = (select auth.uid()));

-- plans: active ones readable by any signed-in user (payment page); all versions for admin
create policy plans_read on public.plans
  for select to authenticated
  using (is_active or (select public.is_super_admin()));

create policy claims_read on public.payment_confirmations
  for select to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));

create policy incoming_admin_read on public.incoming_payments
  for select to authenticated using ((select public.is_super_admin()));

create policy credit_read on public.credit_ledger
  for select to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));

create policy events_read on public.subscription_events
  for select to authenticated
  using (tenant_id = (select public.jwt_tenant_id()) or (select public.is_super_admin()));

create policy reminder_settings_admin_read on public.reminder_settings
  for select to authenticated using ((select public.is_super_admin()));
create policy reminder_settings_admin_update on public.reminder_settings
  for update to authenticated
  using ((select public.is_super_admin())) with check ((select public.is_super_admin()));

create policy queue_admin_read on public.wa_reminder_queue
  for select to authenticated using ((select public.is_super_admin()));
create policy queue_admin_update on public.wa_reminder_queue
  for update to authenticated
  using ((select public.is_super_admin())) with check ((select public.is_super_admin()));

create policy reminder_log_admin_read on public.reminder_log
  for select to authenticated using ((select public.is_super_admin()));
