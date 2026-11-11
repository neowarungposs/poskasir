-- =====================================================================
-- POS SaaS v2 — Step 1a: core schema (tables, indexes, seeds)
-- Source of truth: Playstore_warungposs_v2.txt, Sections 3–5
-- =====================================================================

create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

-- updated_at helper
create or replace function public.touch_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------------
-- Global settings (single row)
-- ---------------------------------------------------------------------
create table public.system_settings (
  id int primary key default 1 check (id = 1),
  monthly_base_price bigint not null default 29000
    check (monthly_base_price > 0 and monthly_base_price % 1000 = 0),
  global_qris_path text,
  bank_details_text text,
  grace_days int not null default 3 check (grace_days between 0 and 30),
  max_offline_days int not null default 7 check (max_offline_days between 1 and 60),
  payment_page_url text,
  deletion_page_url text,
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Tenants & permanent pay codes (D6: never reused)
-- ---------------------------------------------------------------------
create table public.pay_codes (
  code int primary key check (code between 1 and 999),
  tenant_id uuid,
  assigned_at timestamptz not null default now()
);

create table public.tenants (
  id uuid primary key default gen_random_uuid(),
  business_name text not null check (length(trim(business_name)) between 2 and 100),
  owner_user_id uuid not null unique references auth.users(id) on delete cascade,
  customer_code text not null unique,
  email text,
  phone_wa text,
  wa_opt_in boolean not null default false,
  pay_code int not null unique references public.pay_codes(code),
  trial_ends_at timestamptz not null,
  paid_until timestamptz,
  lifecycle text not null default 'LIVE' check (lifecycle in ('LIVE','CHURNED')),
  churned_at timestamptz,
  churn_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.pay_codes
  add constraint pay_codes_tenant_fk foreign key (tenant_id)
  references public.tenants(id) on delete set null;

-- ---------------------------------------------------------------------
-- Outlets & memberships (D5: role lives here, never in user_metadata)
-- ---------------------------------------------------------------------
create table public.outlets (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  code text not null,
  name text not null,
  address text,
  merchant_qris_path text,
  is_main boolean not null default false,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, code)
);
create unique index uq_outlets_one_main on public.outlets(tenant_id) where is_main;

create table public.memberships (
  user_id uuid primary key references auth.users(id) on delete cascade,
  tenant_id uuid references public.tenants(id) on delete cascade,
  outlet_id uuid references public.outlets(id) on delete cascade,
  role text not null check (role in ('SUPER_ADMIN','TENANT_ADMIN','BRANCH_CASHIER')),
  created_at timestamptz not null default now(),
  constraint memberships_tenant_vs_role check ((role = 'SUPER_ADMIN') = (tenant_id is null)),
  constraint memberships_cashier_outlet check (role <> 'BRANCH_CASHIER' or outlet_id is not null)
);

-- ---------------------------------------------------------------------
-- Master data
-- ---------------------------------------------------------------------
create table public.products (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  outlet_id uuid references public.outlets(id) on delete cascade, -- null = all outlets
  name text not null,
  category text,
  price bigint not null check (price >= 0),
  image_path text,
  is_active boolean not null default true,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Sales (ids generated on device: UUIDv7)
-- ---------------------------------------------------------------------
create table public.sales_orders (
  id uuid primary key,
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  outlet_id uuid not null references public.outlets(id) on delete cascade,
  device_id text not null,
  invoice_no text not null,
  cashier_id uuid references auth.users(id) on delete set null,
  payment_method text not null check (payment_method in ('CASH','QRIS','OTHER')),
  total bigint not null check (total >= 0),
  status text not null default 'PAID' check (status in ('PAID','VOIDED')),
  sync_flag text not null default 'OK' check (sync_flag in ('OK','FLAGGED')),
  created_at timestamptz not null,
  synced_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, invoice_no)
);

create table public.sales_order_items (
  id uuid primary key,
  order_id uuid not null references public.sales_orders(id) on delete cascade,
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  product_id uuid references public.products(id) on delete set null,
  product_name text not null,   -- snapshot
  unit_price bigint not null check (unit_price >= 0), -- snapshot
  qty numeric(10,2) not null check (qty > 0),
  subtotal bigint not null check (subtotal >= 0)
);

create table public.void_requests (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  order_id uuid not null references public.sales_orders(id) on delete cascade,
  requested_by uuid references auth.users(id) on delete set null,
  reason text not null,
  status text not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED')),
  decided_by uuid references auth.users(id) on delete set null,
  decided_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index uq_void_one_pending on public.void_requests(order_id) where status = 'PENDING';

create table public.audit_logs (
  id bigint generated always as identity primary key,
  tenant_id uuid references public.tenants(id) on delete cascade,
  actor_id uuid,
  action text not null,
  entity text not null,
  entity_id text,
  payload jsonb,
  created_at timestamptz not null default now()
);

create table public.fcm_tokens (
  token text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text,
  app_flavor text not null check (app_flavor in ('tenant','direct','admin')),
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Billing (D6, D7, D12)
-- ---------------------------------------------------------------------
create table public.plans (
  id uuid primary key default gen_random_uuid(),
  months int not null check (months between 1 and 24),
  normal_price bigint not null check (normal_price > 0),
  discount_pct numeric(5,2) not null default 0 check (discount_pct between 0 and 90),
  price bigint not null check (price > 0 and price % 1000 = 0),
  badge text check (badge in ('PALING HEMAT','POPULER')),
  promo_ends_at timestamptz,
  is_active boolean not null default true,
  retired_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index uq_plans_active_months on public.plans(months) where is_active;
create unique index uq_plans_active_price on public.plans(price) where is_active;

create table public.payment_confirmations (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  plan_id uuid references public.plans(id),
  plan_price_snapshot bigint,
  expected_amount bigint,
  sender_name text,
  proof_path text,
  proof_sha256 text unique,
  transfer_at timestamptz,
  status text not null default 'PENDING'
    check (status in ('PENDING','APPROVED','REJECTED','CANCELLED')),
  rejection_reason text,
  submitted_by uuid references auth.users(id) on delete set null,
  reviewed_by uuid references auth.users(id) on delete set null,
  reviewed_at timestamptz,
  created_at timestamptz not null default now()
);

create table public.incoming_payments (
  id uuid primary key default gen_random_uuid(),
  channel text not null check (channel in ('QRIS','BANK')),
  amount bigint not null check (amount > 0),
  received_at timestamptz not null,
  sender_name text,
  bank_ref text,
  decoded_tenant_id uuid references public.tenants(id) on delete set null,
  matched_tenant_id uuid references public.tenants(id) on delete set null,
  claim_id uuid unique references public.payment_confirmations(id) on delete set null,
  status text not null default 'UNMATCHED'
    check (status in ('UNMATCHED','MATCHED','REFUNDED','IGNORED')),
  note text,
  recorded_by uuid references auth.users(id) on delete set null,
  recorded_at timestamptz not null default now(),
  unique (channel, bank_ref)
);

create table public.credit_ledger (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  amount bigint not null,
  reason text not null,
  incoming_payment_id uuid references public.incoming_payments(id) on delete set null,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

create table public.subscription_events (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  kind text not null check (kind in ('TRIAL_START','EXTEND','CHURN','REVIVE','REFUND','REJECT')),
  months int,
  old_paid_until timestamptz,
  new_paid_until timestamptz,
  incoming_payment_id uuid references public.incoming_payments(id) on delete set null,
  claim_id uuid references public.payment_confirmations(id) on delete set null,
  actor_id uuid references auth.users(id) on delete set null,
  note text,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Reminders (D10, D11)
-- ---------------------------------------------------------------------
create table public.reminder_settings (
  id int primary key default 1 check (id = 1),
  enabled boolean not null default true,
  days_before int[] not null default '{7,3,1}',
  remind_on_grace boolean not null default true,
  run_hour int not null default 9 check (run_hour between 0 and 23),
  wa_template text not null default
    'Halo {nama_usaha}, langganan kasir Anda (kode {kode}) berakhir {tanggal}. {promo_terbaik} Transfer Rp{nominal} di {link}',
  auto_churn_after_days int not null default 30 check (auto_churn_after_days between 1 and 365),
  last_queue_run_date date,
  updated_at timestamptz not null default now()
);

create table public.wa_reminder_queue (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  kind text not null,
  period_end timestamptz not null,
  plan_suggestion_id uuid references public.plans(id),
  amount bigint,
  status text not null default 'PENDING' check (status in ('PENDING','SENT','SKIPPED')),
  sent_at timestamptz,
  sent_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  unique (tenant_id, kind, period_end)
);

create table public.reminder_log (
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  kind text not null,
  period_end timestamptz not null,
  sent_at timestamptz not null default now(),
  primary key (tenant_id, kind, period_end)
);

-- ---------------------------------------------------------------------
-- Indexes (tenant_id everywhere + delta sync + reports + FKs)
-- ---------------------------------------------------------------------
create index idx_pay_codes_tenant on public.pay_codes(tenant_id);
create index idx_tenants_lifecycle on public.tenants(lifecycle);
create index idx_memberships_tenant on public.memberships(tenant_id);
create index idx_memberships_outlet on public.memberships(outlet_id);
create index idx_outlets_tenant_upd on public.outlets(tenant_id, updated_at);
create index idx_products_tenant_upd on public.products(tenant_id, updated_at);
create index idx_products_outlet on public.products(outlet_id);
create index idx_orders_tenant_created on public.sales_orders(tenant_id, created_at);
create index idx_orders_tenant_upd on public.sales_orders(tenant_id, updated_at);
create index idx_orders_outlet on public.sales_orders(outlet_id);
create index idx_orders_cashier on public.sales_orders(cashier_id);
create index idx_items_tenant on public.sales_order_items(tenant_id);
create index idx_items_order on public.sales_order_items(order_id);
create index idx_items_product on public.sales_order_items(product_id);
create index idx_void_tenant_upd on public.void_requests(tenant_id, updated_at);
create index idx_void_order on public.void_requests(order_id);
create index idx_void_requested_by on public.void_requests(requested_by);
create index idx_void_decided_by on public.void_requests(decided_by);
create index idx_audit_tenant on public.audit_logs(tenant_id, created_at);
create index idx_fcm_user on public.fcm_tokens(user_id);
create index idx_claims_tenant on public.payment_confirmations(tenant_id, created_at);
create index idx_claims_status on public.payment_confirmations(status);
create index idx_claims_plan on public.payment_confirmations(plan_id);
create index idx_claims_submitted_by on public.payment_confirmations(submitted_by);
create index idx_claims_reviewed_by on public.payment_confirmations(reviewed_by);
create index idx_incoming_amount on public.incoming_payments(amount);
create index idx_incoming_status on public.incoming_payments(status);
create index idx_incoming_decoded on public.incoming_payments(decoded_tenant_id);
create index idx_incoming_matched on public.incoming_payments(matched_tenant_id);
create index idx_incoming_recorded_by on public.incoming_payments(recorded_by);
create index idx_credit_tenant on public.credit_ledger(tenant_id);
create index idx_credit_incoming on public.credit_ledger(incoming_payment_id);
create index idx_credit_created_by on public.credit_ledger(created_by);
create index idx_events_tenant on public.subscription_events(tenant_id, created_at);
create index idx_events_incoming on public.subscription_events(incoming_payment_id);
create index idx_events_claim on public.subscription_events(claim_id);
create index idx_events_actor on public.subscription_events(actor_id);
create index idx_queue_tenant on public.wa_reminder_queue(tenant_id);
create index idx_queue_status on public.wa_reminder_queue(status);
create index idx_queue_plan on public.wa_reminder_queue(plan_suggestion_id);
create index idx_queue_sent_by on public.wa_reminder_queue(sent_by);

-- updated_at triggers
create trigger trg_touch_system_settings before update on public.system_settings for each row execute function public.touch_updated_at();
create trigger trg_touch_tenants before update on public.tenants for each row execute function public.touch_updated_at();
create trigger trg_touch_outlets before update on public.outlets for each row execute function public.touch_updated_at();
create trigger trg_touch_products before update on public.products for each row execute function public.touch_updated_at();
create trigger trg_touch_sales_orders before update on public.sales_orders for each row execute function public.touch_updated_at();
create trigger trg_touch_void_requests before update on public.void_requests for each row execute function public.touch_updated_at();
create trigger trg_touch_reminder_settings before update on public.reminder_settings for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------------
-- Seeds (editable later by SUPER_ADMIN)
-- ---------------------------------------------------------------------
insert into public.system_settings (id) values (1);
insert into public.reminder_settings (id) values (1);
insert into public.plans (months, normal_price, discount_pct, price, badge) values
  (1,  29000,  0,  29000,  null),
  (3,  87000,  5,  82000,  null),
  (6,  174000, 10, 156000, 'POPULER'),
  (12, 348000, 20, 278000, 'PALING HEMAT');
