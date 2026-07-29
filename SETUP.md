# DriveMP3 v0.1 — Setup

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
| User support email | samil.korkmaz@gmail.com |
| Developer contact | samil.korkmaz@gmail.com |
| Publishing status | leave as **Testing** |

Then add the scope `https://www.googleapis.com/auth/drive.readonly`, and under
**Test users** add `samil.korkmaz@gmail.com`.

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

Note that it is ok to leave SHA-1 certificate fingerprint in this document and publish it in public repo because the fingerprint is a hash of your signing certificate, not the private key, and the certificate ships inside every APK you build. Anyone holding your APK can already read it. So publishing it discloses nothing that distribution wouldn't.

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

## 5. Acceptance check for v0.1

| # | Step | Expected |
| --- | --- | --- |
| 1 | Cold launch, never signed in | Sign-in prompt, **no** consent dialog until you tap |
| 2 | Tap "Sign in with Google" | Account picker, then the Drive consent screen |
| 3 | Grant consent | Your email, MP3 count, and file rows with date + size |
| 4 | Kill and relaunch | Goes straight to the list, no prompt (silent re-auth) |
| 5 | Tap "Sign out", then sign in again | Returns to the list without a second consent screen |
| 6 | Revoke access at <https://myaccount.google.com/permissions>, relaunch | "Drive access was revoked. Sign in again." with a working retry |
| 7 | Enable airplane mode, relaunch | "Network unavailable. Check your connection." with a working retry |

Step 5 is a local sign-out: it drops the in-memory token but leaves the Google-side
grant intact, which is why consent does not reappear. Real revocation ships with the
Settings screen in v0.6.

---

## Where things are

| Path | Role |
| --- | --- |
| [MainActivity.kt](app/src/main/java/com/drivemp3/player/MainActivity.kt) | Hosts Compose; launches the consent `PendingIntent` |
| [auth/DriveAuthManager.kt](app/src/main/java/com/drivemp3/player/auth/DriveAuthManager.kt) | Access tokens via Play Services `AuthorizationClient` |
| [data/DriveApi.kt](app/src/main/java/com/drivemp3/player/data/DriveApi.kt) | Retrofit interface over Drive REST v3 |
| [data/DriveRepository.kt](app/src/main/java/com/drivemp3/player/data/DriveRepository.kt) | The Drive query, field list, and paging constants |
| [ui/LibraryViewModel.kt](app/src/main/java/com/drivemp3/player/ui/LibraryViewModel.kt) | State machine: SignedOut → Loading → Content / Failed |
| [ui/LibraryScreen.kt](app/src/main/java/com/drivemp3/player/ui/LibraryScreen.kt) | The single screen |
| [ServiceLocator.kt](app/src/main/java/com/drivemp3/player/ServiceLocator.kt) | Hand-wired dependencies (no DI framework yet) |

---

## Known v0.1 limitations (by design)

- **First 100 files only**, whole-Drive, newest first. Folder scoping and full paging are v0.2.
- **Matches `mimeType = 'audio/mpeg'` only.** Drive's `contains` operator does prefix
  matching on `name`, so `name contains '.mp3'` does not reliably match `song.mp3`.
  Once v0.2 scopes to a folder, extension filtering happens client-side instead. See
  the comment in [DriveRepository.kt](app/src/main/java/com/drivemp3/player/data/DriveRepository.kt).
- **No app icon** — the launcher shows the default Android icon until v1.0.
- **Token held in memory only.** Rotating the screen is fine (the ViewModel survives);
  a process death re-authorizes silently.
