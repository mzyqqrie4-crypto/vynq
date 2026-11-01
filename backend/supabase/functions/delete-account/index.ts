// Deletes the calling user's account. Removing the auth user cascades to
// profiles and devices (see migrations). Storage purge is added here once
// buckets exist (Phase 3 attachments, avatars).
//
// The service-role key is provided by the Supabase runtime as an env var and
// never leaves the server.
import { createClient } from "npm:@supabase/supabase-js@2.117.3";

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  const jwt = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!jwt) return json(401, { error: "not_authenticated" });

  const admin = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false, autoRefreshToken: false } },
  );

  // Resolve the user from their own access token; never trust a body field.
  const { data, error } = await admin.auth.getUser(jwt);
  if (error || !data.user) return json(401, { error: "not_authenticated" });

  const { error: deleteError } = await admin.auth.admin.deleteUser(data.user.id);
  if (deleteError) {
    console.error("delete-account failed", deleteError.status); // no user data in logs
    return json(500, { error: "delete_failed" });
  }
  return json(200, { deleted: true });
});
