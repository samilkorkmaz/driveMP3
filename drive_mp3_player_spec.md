# Software Requirements Specification (SRS)
## DriveMP3 Player

**Document Version:** 1.0  
**Target Platform:** Android

---

## 1. Overview & Vision
**DriveMP3 Player** is a lightweight, hyper-focused audio player designed to play MP3 audio files hosted on Google Drive. The application streamlines cloud audio playback by automatically managing local caching within a user-defined storage quota, offering clean list sorting by Google Drive file attributes, and providing dedicated playback repeat/shuffle modes without the overhead of heavy ID3/metadata parsing. Inspiration: [CloudPlayer](https://play.google.com/store/apps/details?id=com.doubleTwist.cloudPlayer&hl=en)

---

## 2. Scope & Target Constraints

### 2.1 File Scope
* **Supported File Format:** MP3 (`.mp3`) files **only**. All other audio formats (e.g., FLAC, WAV, AAC, M4A) are ignored during folder scans.
* **Metadata Scope:** **No ID3/metadata tag processing.** The application uses raw Google Drive file names and system upload/creation timestamps exclusively for display and sorting.

### 2.2 Integration Scope
* **Cloud Provider:** Google Drive API v3 (REST API).
* **Authentication:** Google OAuth 2.0 (Single Sign-On).

---

## 3. Detailed Functional Requirements

### 3.1 Google Drive Integration & Synchronization
* **FR-3.1.1 (Authentication):** Users shall sign in using their Google account to grant read/download access to Google Drive.
* **FR-3.1.2 (Folder Selection / Scope):** Upon initial setup, the user can select a specific root folder or search all `.mp3` files across their Google Drive.
* **FR-3.1.3 (File Fetching):** The app shall query Google Drive for files matching `mimeType = 'audio/mpeg'` or ending with `.mp3`.

### 3.2 Automated Caching & Download Limit Management
* **FR-3.2.1 (Auto-Download on Play):** When a track is selected for playback:
  * If the file is not stored locally, playback begins via streaming while concurrently downloading the file to local application storage.
  * If the file is already cached locally, playback begins immediately from local storage.
* **FR-3.2.2 (User Storage Quota):** The user can define a maximum storage limit for offline cache in the settings (e.g., 250 MB, 500 MB, 1 GB, 5 GB, or unlimited).
* **FR-3.2.3 (Cache Eviction Policy - Least Recently Used):**
  * When a new download causes total cache usage to exceed the user's defined storage limit, the system shall automatically delete the oldest cached `.mp3` file based on the **Least Recently Used (LRU)** timestamp.
  * Active playing files shall be protected from LRU eviction until playback stops or moves to another track.
* **FR-3.2.4 (Downloaded Indicator):** Next to each song entry in the track list, the UI shall display a clear **"Downloaded" icon** (e.g., a checkmark or down-arrow badge) if the track is fully cached on the local filesystem.

### 3.3 Song List & Sorting Rules
* **FR-3.3.1 (Sorting Options):** The library view shall support two primary sorting mechanisms:
  1. **Sort by Upload Date:** Uses the `createdTime` metadata attribute from Google Drive.
  2. **Sort by Name:** Uses the raw file title string (`name`) from Google Drive.
* **FR-3.3.2 (Sort Direction Toggle):** Each sorting option shall allow toggling between **Ascending** and **Descending** order.

### 3.4 Playback Engine & Loop Modes
* **FR-3.4.1 (Standard Controls):** Includes Play, Pause, Skip Next, Skip Previous, and a Seek Bar/Scrubber.
* **FR-3.4.2 (Single Track Loop Mode):** When toggled, the currently playing track repeats endlessly upon reaching the end.
* **FR-3.4.3 (Random Play / Shuffle Mode):** When toggled, pressing "Next" or finishing a track selects a random song from the current filtered/sorted list.

---

## 4. User Interface Structure

```
+----------------------------------------------------+
|  DriveMP3 Player                     [ Settings ]  |
+----------------------------------------------------+
|  Sort: [ Upload Date / Name v ]  [ Asc / Desc ^ ]  |
+----------------------------------------------------+
|  [↓] 01_track_one.mp3                              |  <-- [↓] Icon = Local Cache Available
|      02_track_two.mp3                              |
|  [↓] 03_another_song.mp3                           |
|      04_favorite_track.mp3                         |
|      ...                                           |
+----------------------------------------------------+
|  NOW PLAYING: 01_track_one.mp3                     |
|  01:24 ========================o------- 03:45       |
|  [|<]   [ > / || ]   [>|]   [ Loop 1 ]  [ Shuffle ]|
+----------------------------------------------------+
```

---

## 5. Non-Functional Requirements

* **Performance:** Track listing response time should remain under 1 second for folders containing up to 1,000 `.mp3` files.
* **Storage Efficiency:** Only download `.mp3` audio binary payloads; avoid auxiliary metadata indexing files to keep storage lightweight.
* **Offline Resilience:** Once downloaded, tracks must remain fully playable without an active internet connection.

---

## 6. Settings & Configuration
* **Account Status:** Display active Google account email with "Sign Out" option.
* **Storage Limit Slider / Numeric Input:** Configurable cache threshold in Megabytes (MB) or Gigabytes (GB).
* **Storage Bar:** Visual breakdown showing (Used Cache / Max Limit / Total Device Space).
* **Clear Cache Action:** One-tap button to instantly remove all cached `.mp3` files without removing Google Drive cloud references.
