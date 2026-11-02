// Deletes the calling user's account. Their encrypted attachment blobs are
// removed from Storage first; removing the auth user then cascades to
// profiles, devices, keys, contacts and mailbox rows (see migrations).
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
  const userId = data.user.id;

  // Attachments and profile pictures live under "<user id>/" in their private buckets.
  for (const name of ["attachments", "avatars"]) {
    const bucket = admin.storage.from(name);
    for (;;) {
      const { data: files, error: listError } = await bucket.list(userId, { limit: 1000 });
      if (listError) {
        console.error("delete-account: storage list failed"); // no user data in logs
        return json(500, { error: "delete_failed" });
      }
      if (!files || files.length === 0) break;
      const { error: removeError } = await bucket.remove(files.map((f) => `${userId}/${f.name}`));
      if (removeError) {
        console.error("delete-account: storage remove failed");
        return json(500, { error: "delete_failed" });
      }
      if (files.length < 1000) break;
    }
  }

  const { error: deleteError } = await admin.auth.admin.deleteUser(userId);
  if (deleteError) {
    console.error("delete-account failed", deleteError.status);
    return json(500, { error: "delete_failed" });
  }
  return json(200, { deleted: true });
});
