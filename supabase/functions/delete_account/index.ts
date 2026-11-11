// Play Store account deletion (Section 11).
// TENANT_ADMIN: deletes storage files, cashier accounts, the tenant (DB cascade) and the owner account.
// BRANCH_CASHIER (or user without a business): deletes only the caller's account.
import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

async function listFiles(admin: SupabaseClient, bucket: string, prefix: string): Promise<string[]> {
  const out: string[] = [];
  let offset = 0;
  while (true) {
    const { data, error } = await admin.storage.from(bucket).list(prefix, { limit: 1000, offset });
    if (error) throw error;
    if (!data || data.length === 0) break;
    for (const item of data) {
      const path = `${prefix}/${item.name}`;
      if (item.id === null) out.push(...(await listFiles(admin, bucket, path))); // folder
      else out.push(path);
    }
    if (data.length < 1000) break;
    offset += 1000;
  }
  return out;
}

async function removeFolder(admin: SupabaseClient, bucket: string, prefix: string) {
  const files = await listFiles(admin, bucket, prefix);
  for (let i = 0; i < files.length; i += 500) {
    const { error } = await admin.storage.from(bucket).remove(files.slice(i, i + 500));
    if (error) throw error;
  }
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "METHOD_NOT_ALLOWED" }, 405);

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: userData, error: userErr } = await admin.auth.getUser(jwt);
  const user = userData?.user;
  if (userErr || !user) return json({ error: "UNAUTHORIZED" }, 401);

  try {
    const { data: m, error: mErr } = await admin
      .from("memberships").select("user_id, tenant_id, role").eq("user_id", user.id).maybeSingle();
    if (mErr) throw mErr;

    if (m?.role === "SUPER_ADMIN") return json({ error: "SUPER_ADMIN_CANNOT_SELF_DELETE" }, 400);

    if (!m || m.role === "BRANCH_CASHIER") {
      const { error } = await admin.auth.admin.deleteUser(user.id);
      if (error) throw error;
      return json({ deleted: "user" });
    }

    const tenantId: string = m.tenant_id;
    for (const bucket of ["payment-proofs", "outlet-assets"]) await removeFolder(admin, bucket, tenantId);

    const { data: members, error: memErr } = await admin.from("memberships").select("user_id").eq("tenant_id", tenantId);
    if (memErr) throw memErr;
    for (const mm of members ?? []) {
      if (mm.user_id !== user.id) {
        const { error } = await admin.auth.admin.deleteUser(mm.user_id);
        if (error) throw error;
      }
    }

    // Cascades: outlets, products, sales, claims, events, queue... pay_code stays reserved (never reused).
    const { error: tErr } = await admin.from("tenants").delete().eq("id", tenantId);
    if (tErr) throw tErr;
    const { error: uErr } = await admin.auth.admin.deleteUser(user.id);
    if (uErr) throw uErr;

    return json({ deleted: "tenant" });
  } catch (e) {
    console.error("delete_account failed", e);
    return json({ error: "DELETE_FAILED", message: String((e as Error)?.message ?? e) }, 500);
  }
});
