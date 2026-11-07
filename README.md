# Vibe — Zero-Cache, Low-Latency Fire TV Streamer (Kotlin + Rust)

**Vibe** is an ultra-lightweight (~3 MB) streaming client built specifically for **Amazon Fire TV** and **Android TV**.

## Key Architecture Highlights
1. **Hybrid Kotlin + Rust Core (`libvibe_core.so`)**:
   - Compiled for both `armeabi-v7a` (32-bit Fire TV Sticks) and `arm64-v8a` (64-bit Fire TV 4K Max / Cube).
   - Performs zero-allocation URL normalization, CloudStream shortcode resolution, FNV-1a deduplication hashing, and native stream link scraping.
2. **CloudStream 3 & Multi-Source Repo Engine**:
   - Add CloudStream 3 `repo.json` URLs, `plugins.json` indexes, `.m3u` / `.m3u8` playlists, Vibe JSON feeds, or paste any direct stream/webpage URL.
3. **Strictly Ephemeral Zero-Cache Design**:
   - OkHttp configured with `.cache(null)`.
   - Media3 ExoPlayer configured with `backBufferDurationMs = 0` (watched frames are immediately freed from RAM) and `600ms` startup buffer.
   - `ZeroCacheJanitor` automatically wipes `cacheDir`, `codeCacheDir`, and `externalCacheDir` on startup and immediately after every video stops so app storage never grows.
