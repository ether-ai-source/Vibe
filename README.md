# Vibe TV — Simple, Zero-Cache Fire TV Streamer (Kotlin + Rust)

**Vibe TV** is a lightweight (~3 MB), clutter-free streaming client built for **Amazon Fire TV** and **Android TV**.

## Features
- **Easy for Everyone**: Big text, high-contrast yellow D-Pad focus rings, and 3 simple buttons (`Watch`, `Search`, `Add Source`).
- **1-Click Quick-Add Sources**: Add Live TV and Public Movie playlists with a single remote click, or enter CloudStream 3 (`repo.json` / `plugins.json`) and `.m3u` URLs.
- **Silent Zero-Cache Engine**: Never writes video cache to disk and automatically purges temporary files after every video so app size never grows.
- **Rust + ExoPlayer Low-Latency Core**: 600ms startup buffer and native stream scraping for `armeabi-v7a` and `arm64-v8a` Fire TV devices.
