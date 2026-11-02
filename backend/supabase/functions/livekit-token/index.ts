// Issues a short-lived LiveKit token for one call room, only to the caller or
// callee of that call (and only while they're still contacts and the call
// hasn't ended). The room name is the call id; the identity is the device, so
// each phone shows up as its own participant.
//
// Secrets come from the Supabase runtime (set with `supabase secrets set`) and
// never leave the server: LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET.
import { createClient } from "npm:@supabase/supabase-js@2.117.3";
import { AccessToken } from "npm:livekit-server-sdk@2.19.1";

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  const livekitUrl = Deno.env.get("LIVEKIT_URL");
  const apiKey = Deno.env.get("LIVEKIT_API_KEY");
  const apiSecret = Deno.env.get("LIVEKIT_API_SECRET");
  if (!livekitUrl || !apiKey || !apiSecret) return json(503, { error: "calls_not_configured" });

  const jwt = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!jwt) return json(401, { error: "not_authenticated" });

  let body: { call_id?: string; device_id?: string };
  try { body = await req.json(); } catch { return json(400, { error: "invalid_body" }); }
  if (!body.call_id || !UUID.test(body.call_id) || !body.device_id || !UUID.test(body.device_id)) {
    return json(400, { error: "invalid_body" });
  }

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: who, error: authError } = await admin.auth.getUser(jwt);
  if (authError || !who.user) return json(401, { error: "not_authenticated" });
  const userId = who.user.id;

  // The device must be one of the caller's own active devices.
  const { data: device } = await admin.from("devices").select("device_id")
    .eq("device_id", body.device_id).eq("user_id", userId).is("revoked_at", null).maybeSingle();
  if (!device) return json(403, { error: "device_unavailable" });

  const { data: member, error: memberError } = await admin.rpc("call_member", { p_call: body.call_id, p_user: userId });
  if (memberError) return json(500, { error: "lookup_failed" });
  if (!member) return json(403, { error: "not_in_call" });

  const token = new AccessToken(apiKey, apiSecret, {
    identity: `${userId}:${body.device_id}`,
    ttl: "10m", // only for joining; the session itself can last longer
  });
  token.addGrant({ room: body.call_id, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true });
  return json(200, { url: livekitUrl, token: await token.toJwt() });
});
