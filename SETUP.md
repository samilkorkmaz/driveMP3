# DriveMP3 v0.2 — Setup

The project builds already (`./gradlew :app:assembleDebug` passes). What it cannot do
yet is authenticate, because OAuth clients are tied to a Google Cloud project that
only you can create. That is the checklist below — roughly 10 minutes, one time.

---

## 1. Google Cloud project

1. Go to <https://console.cloud.google.com/> and create a project (any name, e.g. `drivemp3`).
2. **APIs & Services → Library →** search "Google Drive API" → **Enable**.

## 2. OAuth consent screen

**APIs & Services → OAuth consent screen**

| Field | Value |
| --- | --- |
| User type | **External** |
| App name | DriveMP3 |
| User support email | `<your-google-account>` |
| Developer contact | `<your-google-account>` |
| Publishing status | leave as **Testing** |

Under **Test users** add `<test-user-google-account>` — the same address in all three fields.

> Testing mode allows up to 100 test users and does not expire. Leave it here —
> moving to Production with this scope triggers OAuth verification plus a CASA
> security assessment. See [VERSION_PLAN.md](VERSION_PLAN.md) section 4.

## 3. Android OAuth client

**APIs & Services → Credentials → Create Credentials → OAuth client ID**

| Field | Value |
| --- | --- |
| Application type | **Android** |
| Package name | `com.drivemp3.player` |
| SHA-1 certificate fingerprint | `F0:32:86:5C:3E:39:58:32:0F:16:BA:2A:2A:3F:CE:50:E8:91:FC:DD` |

> Note that it is ok to leave SHA-1 certificate fingerprint in this document and publish it in public repo because the fingerprint is a hash of your signing certificate, not the private key, and the certificate ships inside every APK you build. Anyone holding your APK can already read it. So publishing it discloses nothing that distribution wouldn't.

That SHA-1 is this machine's debug keystore, read from
`%USERPROFILE%\.android\debug.keystore`. To re-derive it later:

```
./gradlew signingReport
```

**No client ID goes into the app.** Play Services matches the app by package name +
signing certificate, so there is no `google-services.json` and nothing to paste back
into the source. A release build will need its own OAuth client for the release
keystore's SHA-1 — that is a v1.0 task.

---

## 4. Build and run

```
./gradlew :app:assembleDebug          # build only
./gradlew :app:installDebug           # build + install on a connected device
```

Or just open the `d:\driveMP3` folder in Android Studio and press Run.

Requires a device or emulator **with Google Play Services** — pick a Play-enabled
emulator image, not a plain AOSP one, or authorization will fail immediately.

`local.properties` already points at `C:\Users\OEM\AppData\Local\Android\Sdk`. It is
gitignored, so it needs recreating on any other machine.

---

## 5. Acceptance check for v0.2

| # | Step | Expected |
| --- | --- | --- |
| 1 | Cold launch, never signed in | Sign-in prompt, **no** consent dialog until you tap |
| 2 | Tap "Sign in with Google" | Account picker, then the Drive consent screen |
| 3 | Grant consent | Folder picker opens automatically, rooted at "My Drive" |
| 4 | Tap a folder name | Drills in; title updates; back arrow returns |
| 5 | Tap "Use <folder>" | Library appears, scoped to that folder's MP3s |
| 6 | Kill and relaunch | Straight to the library, same folder, no prompt |
| 7 | Tap "Name" / "Upload date" chips | List reorders instantly, no network request |
| 8 | Tap the arrow beside the chips | Direction flips; survives relaunch |
| 9 | Type into the search field | Narrows to names *starting with* what you typed, per keystroke |
| 10 | Type a prefix that matches nothing | "No file names start with “…”." — distinct from the empty-folder message |
| 11 | Type `%` or `_` | Treated literally, not as a wildcard |
| 12 | Clear search with the ✕ | Full list returns |
| 13 | Overflow → Change folder, pick "All of my Drive" | Every MP3 in the account; search box resets |
| 14 | Enable airplane mode, relaunch | List still renders from the index, with an "Offline — showing the last scan" banner; search still works |
| 15 | Revoke access at <https://myaccount.google.com/permissions>, relaunch | "Drive access was revoked. Sign in again." with a working retry |
| 16 | Overflow → Sign out, then sign in again | Returns to the library without a second consent screen |

Steps 7 and 9 are the point of the local index: sorting and search are both SQL
against already indexed rows, so neither re-scans Drive. Step 14 is the same
mechanism — both keep working with no network at all.

Step 12 is a local sign-out: it drops the in-memory token but leaves the Google-side
grant intact, which is why consent does not reappear. Real revocation ships with the
Settings screen in v0.6.

---

## Where things are

| Path | Role |
| --- | --- |
| [MainActivity.kt](app/src/main/java/com/drivemp3/player/MainActivity.kt) | Hosts Compose; launches the consent `PendingIntent`; library ↔ picker routing |
| [auth/DriveAuthManager.kt](app/src/main/java/com/drivemp3/player/auth/DriveAuthManager.kt) | Access tokens via Play Services `AuthorizationClient` |
| [data/DriveApi.kt](app/src/main/java/com/drivemp3/player/data/DriveApi.kt) | Retrofit interface over Drive REST v3 |
| [data/DriveRepository.kt](app/src/main/java/com/drivemp3/player/data/DriveRepository.kt) | Drive queries, `nextPageToken` paging, MP3 filtering |
| [data/TrackRepository.kt](app/src/main/java/com/drivemp3/player/data/TrackRepository.kt) | Bridges Drive and the local index; atomic per-scope refresh |
| [data/SettingsStore.kt](app/src/main/java/com/drivemp3/player/data/SettingsStore.kt) | DataStore: chosen folder and sort order |
| [data/local/TrackDao.kt](app/src/main/java/com/drivemp3/player/data/local/TrackDao.kt) | The four ordered queries backing sort + prefix search |
| [model/LibraryScope.kt](app/src/main/java/com/drivemp3/player/model/LibraryScope.kt) | AllDrive vs Folder scoping |
| [ui/LibraryViewModel.kt](app/src/main/java/com/drivemp3/player/ui/LibraryViewModel.kt) | Combines auth + scope + sort into one UI state |
| [ui/LibraryScreen.kt](app/src/main/java/com/drivemp3/player/ui/LibraryScreen.kt) | Library list and the sort bar |
| [ui/FolderPickerScreen.kt](app/src/main/java/com/drivemp3/player/ui/FolderPickerScreen.kt) | Folder tree browser |
| [ServiceLocator.kt](app/src/main/java/com/drivemp3/player/ServiceLocator.kt) | Hand-wired dependencies (no DI framework yet) |

---

## Known v0.2 limitations (by design)

- **Folder scoping is not recursive.** A folder scope covers that folder's *direct*
  children only. Spec FR-3.1.2 says "select a specific root folder" without settling
  whether subfolders are included; recursion means one Drive query per subfolder,
  which conflicts with the sub-second listing target. "All of my Drive" covers the
  recursive case. Worth revisiting if real libraries turn out to be nested.
- **`.mp3` name matching applies to folder scopes only.** Drive's `contains` operator
  does prefix matching on `name`, so `name contains '.mp3'` cannot match `song.mp3`
  server-side. Folder scopes list all children and filter locally, honouring
  FR-3.1.3 fully; "All of my Drive" must rely on `mimeType = 'audio/mpeg'` alone,
  since listing every file in an account to filter locally is not viable.
- **Search is prefix-only**, matching what was specified: `mid` finds `midnight.mp3`
  but not `the-midnight.mp3`. Substring matching would need a full scan or an FTS
  table.
- **Search covers the indexed scope**, not all of Drive. In a folder scope it only
  matches that folder's files; switch to "All of my Drive" to search everything.
- **No playback yet** — tapping a track does nothing. That is v0.3.
- **No app icon** — the launcher shows the default Android icon until v1.0.
- **Token held in memory only.** Rotation is fine (the ViewModel survives); process
  death re-authorizes silently.
