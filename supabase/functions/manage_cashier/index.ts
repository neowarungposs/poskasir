// TENANT_ADMIN manages branch cashier accounts (client apps cannot create other auth users).
// Actions: invite {email, password, outlet_id} | remove {user_id} | reset_password {user_id, password} | move {user_id, outlet_id}
import { createClient } from "npm:@supabase/supabase-js@2";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "METHOD_NOT_ALLOWED" }, 405);
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: u } = await admin.auth.getUser(jwt);
  if (!u?.user) return json({ error: "UNAUTHORIZED" }, 401);

  const { data: me } = await admin.from("memberships").select("tenant_id, role").eq("user_id", u.user.id).maybeSingle();
  if (me?.role !== "TENANT_ADMIN") return json({ error: "FORBIDDEN" }, 403);
  const tenantId = me.tenant_id as string;

  const body = await req.json().catch(() => ({}));
  const action = body.action as string;

  const outletOk = async (outletId: string) => {
    const { data } = await admin.from("outlets").select("id").eq("id", outletId).eq("tenant_id", tenantId).is("deleted_at", null).maybeSingle();
    return !!data;
  };
  const cashierOk = async (userId: string) => {
    const { data } = await admin.from("memberships").select("user_id").eq("user_id", userId)
      .eq("tenant_id", tenantId).eq("role", "BRANCH_CASHIER").maybeSingle();
    return !!data;
  };
  const audit = (act: string, entityId: string) =>
    admin.from("audit_logs").insert({ tenant_id: tenantId, actor_id: u.user!.id, action: act, entity: "memberships", entity_id: entityId });

  try {
    if (action === "invite") {
      const email = String(body.email ?? "").trim().toLowerCase();
      const password = String(body.password ?? "");
      if (!email.includes("@") || password.length < 6) return json({ error: "INVALID_INPUT" }, 400);
      if (!(await outletOk(body.outlet_id))) return json({ error: "OUTLET_NOT_FOUND" }, 400);
      const { data: created, error } = await admin.auth.admin.createUser({ email, password, email_confirm: true });
      if (error) return json({ error: error.message.includes("already") ? "EMAIL_EXISTS" : error.message }, 400);
      const { error: mErr } = await admin.from("memberships").insert({
        user_id: created.user.id, tenant_id: tenantId, outlet_id: body.outlet_id, role: "BRANCH_CASHIER", email,
      });
      if (mErr) {
        await admin.auth.admin.deleteUser(created.user.id);
        throw mErr;
      }
      await audit("CASHIER_INVITE", created.user.id);
      return json({ user_id: created.user.id });
    }

    if (!(await cashierOk(body.user_id))) return json({ error: "CASHIER_NOT_FOUND" }, 404);

    if (action === "remove") {
      const { error } = await admin.auth.admin.deleteUser(body.user_id);
      if (error) throw error;
      await audit("CASHIER_REMOVE", body.user_id);
      return json({ removed: true });
    }
    if (action === "reset_password") {
      if (String(body.password ?? "").length < 6) return json({ error: "INVALID_INPUT" }, 400);
      const { error } = await admin.auth.admin.updateUserById(body.user_id, { password: body.password });
      if (error) throw error;
      await audit("CASHIER_RESET_PASSWORD", body.user_id);
      return json({ updated: true });
    }
    if (action === "move") {
      if (!(await outletOk(body.outlet_id))) return json({ error: "OUTLET_NOT_FOUND" }, 400);
      const { error } = await admin.from("memberships").update({ outlet_id: body.outlet_id }).eq("user_id", body.user_id);
      if (error) throw error;
      await audit("CASHIER_MOVE", body.user_id);
      return json({ moved: true });
    }
    return json({ error: "UNKNOWN_ACTION" }, 400);
  } catch (e) {
    console.error("manage_cashier failed", e);
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
