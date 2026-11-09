# Listing Prep

Turn a pile of photos into ready-to-post Facebook Marketplace listings, entirely on your Android phone.

![Listing Prep: photos sorted into items, reviewing an item, and posting with the floating copy panel](assets/preview.png)

## What it does

- **Built-in camera.** Shoot every angle of one item, tap *Next item*, repeat. Each group becomes an item, with no gallery picking. Photos stay inside the app.
- **Or sorts existing photos.** *From gallery* lets you pick photos; on-device image matching groups them into items and picks the sharpest as the cover.
- **Tracks what's sold.** Items move from *To post* to *Posted* to *Sold*. Delete a sold item and its photos go too: in-app shots, the copies made for Facebook and, if you choose, the gallery originals.
- **Writes the listing.** Gemma 3n runs on the phone, reads brand names and labels from the photos, and writes a title and description. Nothing is uploaded.
- **Reads barcodes and QR codes** in your photos on the phone and gives them to the AI. If the AI still can't tell what the item is, the barcode number (only that) is looked up online at upcitemdb.com, Open Food Facts or Open Library. You can turn this off in Settings.
- **Writes in the background.** After you shoot, descriptions get written while you do something else; a notification says when they're ready.
- **Your writing style.** Settings → *AI writing style* sets how every description reads (presets or your own instructions).
- **Background clean-up.** After shooting you see a white-background preview of each photo and pick: this photo, all photos, or none. The original is always kept.
- **Undo.** Deleting, merging and status changes show an Undo bar.
- **Write with Google (optional).** Off by default. With your Google AI key, descriptions can be written by Gemini instead of on the phone (faster, sends photos to Google); it falls back to the phone when Google's free limit is reached.
- **Price help (optional).** *Find typical second-hand price* asks Google's AI search what the item sells for used where you live (your own free Google AI Studio key; sends title, condition and location).
- **Bulk select.** Long-press items to merge, mark posted/sold, or delete several at once.
- **You check it.** Each item has a *Checked* switch; sharing stays locked until you've reviewed the photos and text.
- **Posts it fast.** *Share to Marketplace* saves the photos cover-first, opens Facebook, and shows a floating panel with one-tap copy buttons for the title and description. *Done* marks the item posted.

## Install

1. On your phone, open the [latest release](https://github.com/JackLongman12/marketplace-listing-prep/releases/latest) and download `ListingPrep-N.apk`.
2. Open it and allow installs from your browser when Android asks.
3. In the app, go to **Settings → AI model** for the one-time model setup (free, 4.4 GB, Wi-Fi).

After that the app updates itself: it checks this repo's releases, downloads the new build, verifies its checksum and signing key, and installs it when you leave the app. The first update asks you once to allow Listing Prep to install updates.

Built and tested for the Galaxy S26 Ultra (Android 13+, 64-bit ARM, 8 GB+ RAM recommended).

## Privacy

Photos, items and AI processing stay on the phone. The app can only connect to huggingface.co, to start the model download; all other connections are blocked. Shared photos carry no location data. See [SECURITY.md](SECURITY.md) for details and how to report a problem.

## Build from source

GitHub Actions builds and signs every push to `main` (see `.github/workflows/build.yml`). To build locally, open the project in Android Studio, or run `gradle assembleDebug` with JDK 17 and the Android SDK.

## Credits

State / province names come from the ISO 3166-2 data of Debian's [iso-codes](https://salsa.debian.org/iso-codes-team/iso-codes) project (LGPL-2.1), bundled as `app/src/main/assets/subdivisions.json`.
