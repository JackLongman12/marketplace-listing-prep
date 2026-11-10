# Security and privacy

## Reporting a vulnerability

Please report security issues privately through GitHub:
**Security → Report a vulnerability** on this repository. Don't open a public issue.

## What the app does with your data

- **Everything runs on the phone.** Photo grouping, text reading and description writing
  use on-device models (MediaPipe, ML Kit, Gemma 3n). Photos and listings are never uploaded
  by this app.
- **Network allowlist.** The app trusts no certificate authority except for Hugging Face
  (`huggingface.co`, `hf.co` and their subdomains), used only for the one-time model
  download (no account or token is sent). All other connections, including telemetry from bundled libraries, fail. Plain HTTP
  is disabled.
- **Barcode lookup.** Only when the AI can't identify an item and the item has a valid
  retail barcode (EAN/UPC/ISBN check digit verified). Only the number is sent, over HTTPS,
  to `api.upcitemdb.com`, `world.openfoodfacts.org` or `openlibrary.org`; these are the only
  extra hosts the network allowlist permits. Redirects to other hosts are refused, replies
  are capped at 256 KB and treated as untrusted text. Off switch in Settings.
- **Price search.** Only when you tap *Find typical second-hand price*, using your own Google
  AI Studio key (stored encrypted with the Android Keystore, sent in a header). Sends the item
  title, condition and your Settings location to `generativelanguage.googleapis.com` only
  (Gemini with Google Search), over HTTPS, no redirects, replies capped at 1 MB. No photos,
  description or notes. On Google's free tier Google may use requests to improve its products.
- **No cloud writing.** Descriptions are always written on the phone (Gemini Nano or Gemma 3n);
  photos never leave the phone.
- **App updates.** The app reads this repo's latest release from `api.github.com` and
  downloads the APK from GitHub's hosts (HTTPS, no token, nothing about you sent). Before
  installing it checks the published SHA-256, that the package name matches, that the build
  is newer, and that the APK is signed with the same key as the installed app. A build signed
  by anyone else is rejected. Off switch in Settings → Updates.
- **Model download.** No account or token. The file comes from public Hugging Face copies of
  Google's Gemma 3n file (hf.co / huggingface.co hosts only, HTTPS only). Whichever copy it
  comes from, it must match the SHA-256 Google published for the official file, or it's deleted
  and never loaded. Interrupted downloads resume with a freshly signed link.
- **Key storage.** The optional price-search key is encrypted with an AES-256-GCM key held in
  the Android Keystore.
- **Crash reports.** Saved on the phone only, offered for copying on the next start. They hold
  the app build, Android version, phone model, the day, and the error type with the lines of
  code it went through. Error messages are left out (they can contain listing text, file names
  or keys); no photos, items, settings or identifiers. Unless you turn it off (Settings →
  Updates and crash reports), the report is sent on the next start to the crash relay
  (`lp-crash-relay.lp-reports.workers.dev`, source in `relay/`), which posts it as a public
  issue labelled `crash`. The relay accepts only that format, rate-limits, and logs nothing.
- **Shared photos.** Re-encoded as new JPEGs with no EXIF (no GPS location, no camera data).
  The app doesn't request `ACCESS_MEDIA_LOCATION`, so Android hides photo locations from it too.
- **No backups.** App data is excluded from cloud backup and device-to-device transfer.
- **Recents.** The app hides its content from the Recents switcher.
- **Clipboard.** Text copied by the posting panel is cleared when the panel closes.
- **Camera photos.** Kept in the app's private storage, never added to the gallery, no
  location recorded. Deleted with their item.
- **Uninstall.** Android asks whether to keep app data. Unticked (default), uninstalling
  removes items, camera photos, the model, the price-search key and its encryption key. Ticked, they stay for the
  next install of this app (same signing key only). Photo
  copies in `Pictures/ListingPrep` are deleted when an item is posted or deleted; Settings →
  Privacy → *Erase everything* removes all remaining app data before you uninstall.

## Build and release integrity

- Third-party GitHub Actions are pinned to commit SHAs; Dependabot proposes updates.
- Every Gradle dependency is pinned by SHA-256 (`gradle/verification-metadata.xml`).
- The bundled image-embedding model is checked against a pinned SHA-256 at build time.
- Each release includes the APK's SHA-256 so you can verify your download.
- Each build's summary lists the permissions and components in the shipped APK.
