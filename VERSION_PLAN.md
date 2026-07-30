# DriveMP3 Player — Version Plan

**Document Version:** 1.0
**Date:** 2026-07-29
**Companion to:** [drive_mp3_player_spec.md](drive_mp3_player_spec.md)

---

## 1. Stack Decision

**Native Android, Kotlin + Jetpack Compose (Material 3).**

Decided against React Native / Flutter / KMP on two grounds:

1. **Android is the only target.** Cross-platform frameworks earn their abstraction tax on the second platform; there isn't one.
2. **The spec's hard requirements are native media plumbing.** FR-3.2.1 (stream while concurrently downloading), FR-3.2.3 (LRU eviction with the playing track pinned), and FR-3.2.4 (accurate "Downloaded" badge) map onto ExoPlayer's `CacheDataSource` + `SimpleCache` almost 1:1. Through `react-native-track-player` you get ExoPlayer's *internal* cache with its own LRU — files you cannot enumerate, badge per-row, or evict under your own pinning rule — forcing either a double fetch per track or dropping stream-while-downloading entirely.

Secondary factor: no prior fluency in either stack, so one toolchain (Android Studio + Gradle + Kotlin) beats two (those plus Node + Metro + a config-plugin layer).

### 1.1 Key library choices

| Concern | Choice | Rationale |
| --- | --- | --- |
| Drive API | Retrofit + OkHttp + kotlinx.serialization against REST v3 directly | `google-api-services-drive` is heavy, blocking, and awkward on Android. Only ~3 endpoints are needed: `files.list`, `files.get?alt=media`, `about.get`. |
| Identity | Credential Manager (`androidx.credentials`) | `GoogleSignIn` is deprecated. |
| Drive authorization | Play Services `AuthorizationClient` | Supplies the OAuth access token for the Drive scope. |
| Playback | Media3 ExoPlayer + `MediaSessionService` | Background playback, notification controls, audio focus. |
| Cache | `CacheDataSource` + `SimpleCache` over app-private storage | Write-through cache = stream-while-downloading for free. |
| Local index | Room | Offline library rendering, LRU timestamps, cache state. |
| Preferences | DataStore | Folder ID, quota, sort state. |

**Note on spec §5:** "avoid auxiliary metadata indexing files" is read as *no ID3/artwork sidecars*. A small Room index is required for offline listing, the <1s/1000-file target, and LRU bookkeeping.

---

## 2. Sequencing Principle

Risk first. Auth and authenticated streaming are the two places this project can genuinely stall, so each gets its own early version. UI polish, settings, and quota management are deferred — they are well-understood work.

---

## 3. Versions

### v0.1 — Sign in and list MP3s

**Goal:** prove the whole auth-to-Drive chain end to end. One screen. No player, no cache, no database.

- Single Compose screen, two states: signed-out (a "Sign in with Google" button) and signed-in (account email, "Sign out", scrollable list).
- Request scope `https://www.googleapis.com/auth/drive.readonly`; obtain an access token via `AuthorizationClient`.
- One query, whole-Drive, first page only:
  - `q=(mimeType='audio/mpeg' or name contains '.mp3') and trashed=false`
  - `fields=nextPageToken,files(id,name,size,createdTime)`
  - `pageSize=100`
- Rows show raw file name, created date, size. No icons, no sort controls, no folder selection.
- Access token in memory only; silent re-authorization on relaunch once the grant exists.

**Non-code prerequisites (blocking):** Google Cloud project; Drive API enabled; OAuth consent screen set to **External / Testing** with your own email as a test user; Android OAuth client registered with the package name + debug SHA-1 fingerprint.

**Done when:** cold launch → sign in → real MP3 file names from your Drive appear; sign out and back in works; revoking access in your Google Account and relaunching recovers cleanly.

---

### v0.2 — Folder scope, sorting, search, full library

*Satisfies FR-3.1.2, FR-3.3.1, FR-3.3.2*

- In-app folder browser (`q=mimeType='application/vnd.google-apps.folder'`, drill down by parent), plus an "All of my Drive" option. There is no native Android Drive folder picker — the Google Picker is a web/JS component — so a small browser screen is the pragmatic route.
- Persist the chosen folder ID in DataStore; subsequent launches go straight to the library.
- Full `nextPageToken` pagination to cover 1,000+ files.
- Sort by `createdTime` or `name`, each with an asc/desc toggle. Server-side via `orderBy` where possible, client-side fallback.
- Room index so the list renders instantly and offline, hitting the <1s/1000-files target.
- **Incremental file-name search.** A search field above the list filters to names
  *starting with* the entered string, narrowing on every keystroke. Runs entirely
  against the Room index as a SQL prefix match, so it issues no Drive request and
  works offline. Case-insensitive across non-ASCII names, which rules out SQLite's
  ASCII-only `LOWER()` and means storing a pre-lowercased column at index time.
  Search state is deliberately *not* persisted — restoring a stale query on relaunch
  would read as a broken library.

> **Not in scope:** substring or fuzzy matching. Prefix-only is what was asked for,
> and it is the form a `LIKE 'x%'` index can serve. Substring search would need
> either a full scan or an FTS table.

---

### v0.3 — Streaming playback

*Satisfies FR-3.4.1*

- Media3 ExoPlayer, foreground only. Tap a row → stream `files/{id}?alt=media`.
- Custom `DataSource.Factory` injecting `Authorization: Bearer …`, and — critically — refreshing an expired token mid-track and resuming at the current position.
- Now-playing bar: title, elapsed/total, seek bar, play/pause.

Second real risk after auth; isolated deliberately.

---

### v0.4 — Transport, modes, background playback

*Satisfies FR-3.4.2, FR-3.4.3*

- Skip next / previous across the current sorted list.
- Loop-single toggle; shuffle toggle picking randomly from the active sorted/filtered list.
- `MediaSessionService` + notification controls, audio focus handling, headset and Bluetooth media buttons, playback surviving screen-off and backgrounding.

---

### v0.5 — Download on play and local cache

*Satisfies FR-3.2.1, FR-3.2.4*

- On play: if not cached, stream *and* populate the cache in one read; if cached, play locally and immediately.
- Atomic writes (temp file + rename) so a killed download never leaves a corrupt entry.
- "Downloaded" badge per row, driven by the Room index.
- **No quota enforcement yet** — the cache grows unbounded on purpose, so v0.6's eviction can be built and tested against a real, full cache.

---

### v0.6 — Quota, LRU eviction, Settings

*Satisfies FR-3.2.2, FR-3.2.3, spec §6*

- Settings screen: account email + Sign Out, quota selector (250 MB / 500 MB / 1 GB / 5 GB / unlimited), storage bar (used cache / max limit / device free), Clear Cache.
- LRU eviction keyed on `lastPlayedAt`, triggered when a download would breach the quota. The currently-playing file is pinned until playback moves on.
- Edge cases to handle explicitly: a single file larger than the entire quota; quota lowered below current usage.

---

### v0.7 — Offline resilience and hardening

*Satisfies spec §5*

- Detect no-network and offer a cached-only library view; block or clearly fail plays on uncached tracks.
- Real error states: expired grant, revoked access, 403 rate-limit with backoff, empty folder, file deleted on Drive but still in the local index.
- Performance pass against a 1,000-file folder.

---

### v1.0 — Release

App icon, dark theme verification, crash reporting, R8/ProGuard rules, release signing, Play Store listing and Data Safety form — and the OAuth scope question below resolved.

---

## 4. Open Decision — OAuth Restricted Scope

**Decide before investing in v1.0 polish.**

`drive.readonly` is a Google **restricted** scope. Personal and testing use is unaffected: OAuth consent screen "Testing" mode allows up to 100 test users indefinitely. A **public Play Store listing** requires Google OAuth verification plus an annual **CASA security assessment** — cost and calendar time, not code.

The alternative is `drive.file` + Google Picker: non-restricted, but grants access only to files the user explicitly picks, and the Picker is a web component needing a WebView flow on Android. Worse UX, more work, and a poor fit for "scan a folder of 500 MP3s."

**Plan:** build v0.1–v0.5 on `drive.readonly` in Testing mode. If the app stays on personal devices, Testing mode is the permanent answer and no further action is required.

---

## 5. Risk Register

| Risk | Version | Mitigation |
| --- | --- | --- |
| Restricted-scope verification / CASA cost | v1.0 | Stay in Testing mode; decide distribution before v1.0. See §4. |
| Access token expiry mid-stream | v0.3 | Token-refreshing `DataSource` that resumes at the current position. |
| Drive download quotas / rate limits from repeated streaming | v0.3–v0.5 | Exponential backoff on 403; pull cache work earlier if it bites in practice. |
| `<1s` listing for 1,000 files | v0.2 | Room index + pagination; render from local index, refresh in background. |
| Compose and coroutines/Flow learning curve | v0.1–v0.2 | v0.1 needs neither in depth — one screen, one network call. |
