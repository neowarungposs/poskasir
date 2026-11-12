// Server push (FCM HTTP v1). Called by the database through pg_net with a Vault secret.
// Needs Edge Function secret FCM_SERVICE_ACCOUNT (Firebase service account JSON).
// All messages are data-only so the app can react (e.g. refresh entitlement) and draw the notification itself.
import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

type Sa = { project_id: string; client_email: string; private_key: string };

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const b64url = (data: ArrayBuffer | Uint8Array | string) => {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

let cachedToken: { token: string; exp: number } | null = null;

async function accessToken(sa: Sa): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.token;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claim}`));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claim}.${b64url(sig)}`,
    }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(`oauth: ${JSON.stringify(body)}`);
  cachedToken = { token: body.access_token, exp: now + (body.expires_in ?? 3600) };
  return cachedToken.token;
}

async function push(
  db: SupabaseClient, sa: Sa, tokens: string[], title: string, body: string,
  data: Record<string, string> = {}, channel = "general",
) {
  if (tokens.length === 0) return 0;
  const token = await accessToken(sa);
  let sent = 0;
  for (const t of tokens) {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: { token: t, data: { ...data, title, body, channel }, android: { priority: "HIGH" } },
      }),
    });
    if (res.ok) { sent++; continue; }
    const err = await res.text();
    if (res.status === 404 || err.includes("UNREGISTERED") || err.includes("INVALID_ARGUMENT")) {
      await db.from("fcm_tokens").delete().eq("token", t); // stale token cleanup
    } else {
      console.error("fcm error", res.status, err);
    }
  }
  return sent;
}

async function tokensFor(db: SupabaseClient, userIds: string[], flavors: string[]) {
  if (userIds.length === 0) return [];
  const { data } = await db.from("fcm_tokens").select("token").in("user_id", userIds).in("app_flavor", flavors);
  return (data ?? []).map((r) => r.token as string);
}

async function adminTokens(db: SupabaseClient) {
  const { data } = await db.from("memberships").select("user_id").eq("role", "SUPER_ADMIN");
  return tokensFor(db, (data ?? []).map((r) => r.user_id), ["admin"]);
}

async function tenantTokens(db: SupabaseClient, tenantId: string, role?: string) {
  let q = db.from("memberships").select("user_id").eq("tenant_id", tenantId);
  if (role) q = q.eq("role", role);
  const { data } = await q;
  return tokensFor(db, (data ?? []).map((r) => r.user_id), ["tenant", "direct"]);
}

const rp = (n: number) => "Rp" + Math.round(n).toLocaleString("id-ID");
const tgl = (iso: string | Date) =>
  new Intl.DateTimeFormat("id-ID", { timeZone: "Asia/Jakarta", day: "numeric", month: "short", year: "numeric" })
    .format(new Date(iso));
const wibDate = (d: Date) =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Jakarta" }).format(d); // YYYY-MM-DD

async function handle(db: SupabaseClient, sa: Sa, type: string, id?: string) {
  switch (type) {
    case "NEW_CLAIM": {
      const { data: c } = await db.from("payment_confirmations")
        .select("expected_amount, tenants(business_name, customer_code)").eq("id", id).single();
      const t = (c as any)?.tenants;
      return push(db, sa, await adminTokens(db), "Pembayaran baru",
        `${t?.business_name ?? "-"} (${t?.customer_code ?? "-"}) • ${c?.expected_amount ? rp(c.expected_amount) : "tanpa paket"}`,
        { type: "NEW_CLAIM", id: id ?? "" });
    }
    case "SUBSCRIPTION_EVENT": {
      const { data: e } = await db.from("subscription_events").select("tenant_id, kind, new_paid_until, note").eq("id", id).single();
      if (!e) return 0;
      const tokens = await tenantTokens(db, e.tenant_id);
      if (e.kind === "EXTEND") {
        return push(db, sa, tokens, "Langganan diperpanjang", `Terima kasih! Aktif sampai ${tgl(e.new_paid_until)}.`,
          { type: "ENTITLEMENT_UPDATED" }, "subscription");
      }
      return push(db, sa, tokens, "Konfirmasi pembayaran ditolak", `Alasan: ${e.note ?? "-"}. Hubungi admin bila sudah transfer.`,
        { type: "ENTITLEMENT_UPDATED" }, "subscription");
    }
    case "VOID_REQUEST": {
      const { data: v } = await db.from("void_requests")
        .select("tenant_id, reason, sales_orders(invoice_no, total)").eq("id", id).single();
      if (!v) return 0;
      const o = (v as any).sales_orders;
      return push(db, sa, await tenantTokens(db, v.tenant_id, "TENANT_ADMIN"), "Permintaan void",
        `${o?.invoice_no ?? "-"} (${o?.total ? rp(o.total) : "-"}): ${v.reason}`, { type: "VOID_REQUEST", id: id ?? "" });
    }
    case "VOID_DECIDED": {
      const { data: v } = await db.from("void_requests")
        .select("requested_by, status, sales_orders(invoice_no)").eq("id", id).single();
      if (!v?.requested_by) return 0;
      const o = (v as any).sales_orders;
      return push(db, sa, await tokensFor(db, [v.requested_by], ["tenant", "direct"]),
        v.status === "APPROVED" ? "Void disetujui" : "Void ditolak", o?.invoice_no ?? "",
        { type: "VOID_DECIDED", id: id ?? "" });
    }
    case "DAILY":
      return daily(db, sa);
  }
  return 0;
}

async function daily(db: SupabaseClient, sa: Sa) {
  const { data: s } = await db.from("system_settings").select("grace_days").eq("id", 1).single();
  const grace = s?.grace_days ?? 3;
  const { data: tenants } = await db.from("tenants")
    .select("id, customer_code, trial_ends_at, paid_until").eq("lifecycle", "LIVE");
  const today = wibDate(new Date());
  let sent = 0;
  for (const t of tenants ?? []) {
    const access = new Date(Math.max(new Date(t.trial_ends_at).getTime(), t.paid_until ? new Date(t.paid_until).getTime() : 0));
    const graceEnd = new Date(access.getTime() + grace * 86400000);
    const days = Math.round((Date.parse(wibDate(access)) - Date.parse(today)) / 86400000);
    let kind: string | null = null;
    let body = "";
    if ([7, 3, 2, 1].includes(days)) { kind = `H-${days}`; body = `Langganan berakhir ${days} hari lagi (${tgl(access)}).`; }
    else if (days === 0) { kind = "H0"; body = `Langganan berakhir hari ini (${tgl(access)}).`; }
    else if (days < 0 && Date.now() <= graceEnd.getTime()) { kind = `GRACE-${today}`; body = `Masa tenggang sampai ${tgl(graceEnd)}. Kasir masih bisa dipakai.`; }
    else if (days < 0 && Date.now() > graceEnd.getTime()) { kind = "EXPIRED"; body = "Langganan berakhir. Aplikasi dalam mode baca saja."; }
    if (!kind) continue;
    // reminder_log dedupes per tenant + kind + period
    const { error } = await db.from("reminder_log").insert({ tenant_id: t.id, kind, period_end: access.toISOString() });
    if (error) continue; // already sent
    sent += await push(db, sa, await tenantTokens(db, t.id), "Status langganan", `${body} Kode pelanggan: ${t.customer_code}`,
      { type: "ENTITLEMENT_UPDATED" }, "subscription");
  }

  const count = async (table: string, col: string, val: string) =>
    (await db.from(table).select("id", { count: "exact", head: true }).eq(col, val)).count ?? 0;
  const claims = await count("payment_confirmations", "status", "PENDING");
  const money = await count("incoming_payments", "status", "UNMATCHED");
  const queue = await count("wa_reminder_queue", "status", "PENDING");
  if (claims + money + queue > 0) {
    sent += await push(db, sa, await adminTokens(db), "Ringkasan hari ini",
      `${queue} tenant perlu diingatkan via WA • ${claims} klaim pending • ${money} uang masuk belum dicocokkan`,
      { type: "DAILY" });
  }
  return sent;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "METHOD_NOT_ALLOWED" }, 405);
  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const secret = req.headers.get("x-webhook-secret") ?? "";
  const { data: ok } = await db.rpc("check_webhook_secret", { p_secret: secret });
  if (ok !== true) return json({ error: "UNAUTHORIZED" }, 401);

  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!raw) {
    console.warn("FCM_SERVICE_ACCOUNT not set; push skipped");
    return json({ skipped: "FCM_SERVICE_ACCOUNT missing" });
  }
  try {
    const sa = JSON.parse(raw) as Sa;
    const { type, id } = await req.json();
    const sent = await handle(db, sa, type, id);
    return json({ type, sent });
  } catch (e) {
    console.error("notify failed", e);
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
