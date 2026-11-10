# Crash relay

A small Cloudflare Worker that turns the app's anonymous crash reports into issues on this
repo's public issue board (label `crash`). The GitHub key lives only in Cloudflare as an
encrypted secret, never in the app.

What a report contains: app build, Android version, phone model, the day, and the error type
with the lines of code it went through. No error messages, photos, listings or identifiers.
The relay only accepts that exact format (so nobody can post their own text through it),
rate-limits senders, merges repeats of the same crash into one issue, and logs nothing.

## Setup (once)

1. **GitHub key** at <https://github.com/settings/personal-access-tokens/new>:
   - Token name: `crash relay`; Expiration: 1 year
   - Repository access: *Only select repositories* → `marketplace-listing-prep`
   - Permissions → Repository → **Issues: Read and write** (nothing else)
   - Generate, and copy the token. Don't share it with anyone.
2. **Deploy** from this folder (needs Node.js):
   ```
   npx wrangler login
   npx wrangler deploy
   npx wrangler secret put GITHUB_TOKEN
   ```
   Paste the token when asked. If Cloudflare asks you to pick a `workers.dev` subdomain,
   choose something neutral (not your name): it's part of a public address.
3. The deploy prints the address, e.g. `https://lp-crash-relay.<subdomain>.workers.dev`.
   The app sends to that address + `/crash`.

When the key expires, make a new one and run `npx wrangler secret put GITHUB_TOKEN` again.
