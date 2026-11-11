-- =====================================================================
-- POS SaaS v2 — Step 1d: private storage buckets + policies
-- Object path convention: {tenant_id}/...  (system-assets: any path)
-- =====================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('payment-proofs', 'payment-proofs', false, 2097152, array['image/webp','image/jpeg','image/png']),
  ('outlet-assets',  'outlet-assets',  false, 2097152, array['image/webp','image/jpeg','image/png']),
  ('system-assets',  'system-assets',  false, 2097152, array['image/webp','image/jpeg','image/png'])
on conflict (id) do nothing;

-- read: own tenant folder, or SUPER_ADMIN
create policy "tenant_read_own_objects" on storage.objects
  for select to authenticated
  using (
    bucket_id in ('payment-proofs','outlet-assets')
    and ((storage.foldername(name))[1] = (select public.jwt_tenant_id())::text
         or (select public.is_super_admin()))
  );

-- payment proofs: TENANT_ADMIN uploads into own folder (no update/delete = tamper-proof)
create policy "tenant_admin_upload_proofs" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'payment-proofs'
    and (storage.foldername(name))[1] = (select public.jwt_tenant_id())::text
    and (select public.jwt_app_role()) = 'TENANT_ADMIN'
  );

-- outlet assets (logo, merchant QRIS): TENANT_ADMIN manages own folder
create policy "tenant_admin_insert_outlet_assets" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'outlet-assets'
    and (storage.foldername(name))[1] = (select public.jwt_tenant_id())::text
    and (select public.jwt_app_role()) = 'TENANT_ADMIN'
  );
create policy "tenant_admin_update_outlet_assets" on storage.objects
  for update to authenticated
  using (
    bucket_id = 'outlet-assets'
    and (storage.foldername(name))[1] = (select public.jwt_tenant_id())::text
    and (select public.jwt_app_role()) = 'TENANT_ADMIN'
  )
  with check (
    bucket_id = 'outlet-assets'
    and (storage.foldername(name))[1] = (select public.jwt_tenant_id())::text
    and (select public.jwt_app_role()) = 'TENANT_ADMIN'
  );
create policy "tenant_admin_delete_outlet_assets" on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'outlet-assets'
    and (storage.foldername(name))[1] = (select public.jwt_tenant_id())::text
    and (select public.jwt_app_role()) = 'TENANT_ADMIN'
  );

-- system assets (global QRIS): any signed-in user reads, SUPER_ADMIN writes
create policy "system_assets_read" on storage.objects
  for select to authenticated using (bucket_id = 'system-assets');
create policy "system_assets_admin_insert" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'system-assets' and (select public.is_super_admin()));
create policy "system_assets_admin_update" on storage.objects
  for update to authenticated
  using (bucket_id = 'system-assets' and (select public.is_super_admin()))
  with check (bucket_id = 'system-assets' and (select public.is_super_admin()));
create policy "system_assets_admin_delete" on storage.objects
  for delete to authenticated
  using (bucket_id = 'system-assets' and (select public.is_super_admin()));
