/**
 * Listing Prep crash relay (Cloudflare Worker).
 *
 * The app POSTs an anonymous crash report to /crash; this turns it into a public GitHub issue
 * (or a "seen again" comment on the existing one). The GitHub key lives only here, as an
 * encrypted Worker secret, never in the app.
 *
 * Abuse protection (the address is public, since it's inside the app):
 * - Only the exact report format is accepted: two fixed header lines, then Java class names
 *   and "  at Class.method(File.kt:123)" lines. No free text, no "@mentions", no links,
 *   no backticks, so nobody can post their own messages through it. Max 8 KB.
 * - Rate limits: a few reports per minute per sender, and a global cap.
 * - The same crash goes into one issue; repeats add a short comment (capped).
 *
 * Privacy: nothing is stored or logged here (observability is off in wrangler.toml). The
 * sender's IP is used only as the rate-limit key, in Cloudflare's short-lived counter.
 */

const MAX_BYTES = 8000;
const MAX_REPEAT_COMMENTS = 20;

const HEADER = /^Listing Prep build \d{1,7}, Android [0-9A-Za-z.]{1,12} \(SDK \d{1,3}\), [0-9A-Za-z _.()+\/-]{1,40}$/;
const DAY = /^Crash on \d{4}-\d{2}-\d{2}$/;
const CLASS = /^(Caused by )?[A-Za-z_$][\w$.]{0,200}$/;
const FRAME = /^  at [\w$.<>\-]{1,300}\([\w$.?\-]{1,120}:-?\d{1,7}\)$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/crash") return reply(404);

    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    const [perSender, overall] = await Promise.all([
      env.PER_SENDER.limit({ key: ip }),
      env.OVERALL.limit({ key: "all" }),
    ]);
    if (!perSender.success || !overall.success) return reply(429);

    if (Number(request.headers.get("content-length") || 0) > MAX_BYTES) return reply(413);
    const text = await request.text();
    if (text.length > MAX_BYTES) return reply(413);

    const lines = text.replace(/\r/g, "").replace(/\n+$/, "").split("\n");
    if (!valid(lines)) return reply(400);

    // Same crash = same error types and code lines (the header with build/phone/day aside).
    const trace = lines.slice(2).filter((l) => l !== "");
    const sig = (await sha256(trace.join("\n"))).slice(0, 12);
    const error = trace[0].split(".").pop();
    const place = (trace.find((l) => l.startsWith("  at com.listingprep.")) || trace[1] || "")
      .replace(/^  at /, "").replace(/\(.*$/, "").split(".").slice(-2).join(".");
    const title = `Crash: ${error}${place ? ` in ${place}` : ""} [${sig}]`;

    const gh = (path, init = {}) =>
      fetch(`https://api.github.com${path}`, {
        ...init,
        redirect: "manual", // a redirect is just a failed (non-2xx) reply here
        headers: {
          Authorization: `Bearer ${env.GITHUB_TOKEN}`,
          Accept: "application/vnd.github+json",
          "User-Agent": "listing-prep-crash-relay",
          "X-GitHub-Api-Version": "2022-11-28",
          ...(init.body ? { "Content-Type": "application/json" } : {}),
        },
      });

    const repo = env.GITHUB_REPO;
    const q = encodeURIComponent(`repo:${repo} is:issue in:title "[${sig}]"`);
    const found = await gh(`/search/issues?q=${q}&per_page=1`);
    if (!found.ok) return reply(502);
    const existing = (await found.json()).items?.[0];

    if (existing) {
      if (existing.comments < MAX_REPEAT_COMMENTS) {
        const r = await gh(`/repos/${repo}/issues/${existing.number}/comments`, {
          method: "POST",
          body: JSON.stringify({ body: `Seen again: ${lines[0]}, ${lines[1].toLowerCase()}` }),
        });
        if (!r.ok) return reply(502);
      }
      return reply(200);
    }

    const body =
      "Sent automatically by the app after a crash. It contains only the app build, Android " +
      "version, phone model, the day, and the error type with the code lines it went through " +
      "(no error messages, photos, listings or personal data).\n\n```\n" + lines.join("\n") + "\n```";
    const created = await gh(`/repos/${repo}/issues`, {
      method: "POST",
      body: JSON.stringify({ title, body, labels: ["crash"] }),
    });
    return reply(created.ok ? 201 : 502);
  },
};

function valid(lines) {
  if (lines.length < 4 || lines.length > 140) return false;
  if (!HEADER.test(lines[0]) || !DAY.test(lines[1])) return false;
  let sawClass = false;
  for (const l of lines.slice(2)) {
    if (l === "") continue;
    if (FRAME.test(l)) continue;
    if (CLASS.test(l)) { sawClass = true; continue; }
    return false;
  }
  return sawClass;
}

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function reply(status) {
  return new Response(null, { status });
}
