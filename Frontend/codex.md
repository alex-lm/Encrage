# Encrage Android frontend

Encrage is a fully native Android client for a private, client-side encrypted photo and video cloud. Its main design goals are a smooth local gallery, reliable background backup, offline operation, bounded memory use for large files, and an encryption model in which the backend never receives plaintext media or the media master key.

The application ID is `com.encrage.vault` (debug builds use `com.encrage.vault.debug`). It supports Android 8.0+ (`minSdk 26`) and targets API 36. App version is `0.1.0`.

## Technology

- Kotlin, Coroutines and Flow
- Jetpack Compose with Material 3
- Navigation 3 and ViewModels
- Hilt dependency injection
- Room as the gallery source of truth
- Paging 3 for large libraries
- MediaStore and `ContentObserver` for local media discovery
- WorkManager for reconciliation and background uploads
- Retrofit and OkHttp for the backend API
- DataStore for non-sensitive preferences
- Coil 3 for local gallery images
- Media3/ExoPlayer for local video playback
- Android Keystore for device-bound secret wrapping
- LazySodium/libsodium for XChaCha20-Poly1305

Build tooling: Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21, JDK 17, compile/target SDK 36.

## Architecture

The app follows an offline-first data flow:

```text
Compose UI
    -> ViewModels
    -> repositories/coordinators
    -> Room + MediaStore + remote API
```

Room is the only data source rendered by the gallery. MediaStore scans and cloud synchronization merge records into Room; Compose does not wait for network requests to display locally known media.

The source tree is divided by responsibility:

```text
com.encrage.vault/
    backup/       WorkManager scheduling and resumable upload pipeline
                  (BackupWorker lives in BackupScheduler.kt)
    crypto/       key storage, derivation, fingerprints and chunk encryption
    data/local/   Room entities, DAOs and database
    data/remote/  Retrofit contracts, authentication and session handling
    data/repository/
                  Room-backed gallery and cloud metadata synchronization
    data/preferences/
                  non-sensitive DataStore settings
    media/        MediaStore scanning, observation and thumbnails
                  (ThumbnailGenerator lives in MediaStoreScanner.kt)
    ui/           authentication, Photos, Backup and Settings Compose UI
    di/           Hilt providers and bindings
```

`EncrageApplication` configures Hilt-backed WorkManager, periodic reconciliation and the live MediaStore observer. `MainActivity` owns permission and biometric prompts, then hosts `AppRoot`.

## Startup and authentication

At startup, `SessionRepository` looks for a username in DataStore and a wrapped refresh token in `SecretStore`.

- No saved credentials: show the login screen.
- Saved credentials: enter the app immediately (offline), then refresh in the background.
- Secrets are checked by presence, not by decryption. The Keystore wrapping keys require an unlocked device, so a locked phone must never look like "signed out" or "no Recovery Key". `MasterKeyManager.isUsable()` reports whether the key can be unwrapped right now; `BackupWorker` retries later when it can't.
- No network or a transient server error: preserve the local session and remain usable offline.
- HTTP 400, 401 or 403 from refresh: treat the refresh credential as invalid and clear the authentication session.
- Signed in without a confirmed master key (or with a pending generated key): show `EncryptionSetupScreen` before the main shell.

Passwords are sent only to `POST /auth/login` and the authenticated `POST /auth/change-password`; they are never persisted. Settings validates that a replacement password is at least 12 characters, differs from the current password and matches its confirmation before requesting the change. A successful change keeps the current session, while the backend revokes the user's other sessions. Access tokens remain in memory. Refresh tokens are encrypted with an AES-256-GCM key held by Android Keystore before being written under `noBackupFilesDir`.

`BearerInterceptor` adds access tokens to normal requests. Every refresh (startup restore and the 401 `RefreshAuthenticator`) goes through `TokenRefresher`, which holds one lock and skips the network call if another caller already rotated the token. The server revokes a session when a rotated refresh token is reused, so this must stay single-flight across both paths.

`ServerReachability` (fed by `ReachabilityInterceptor` on the raw OkHttp client) records whether the server answered its last request. It is a UI hint only. The OkHttp client uses an 8 s connect timeout and 45 s read/write timeouts.

`TokenRefresher` persists the rotated refresh token before exposing the new access token.

Logout clears tokens and username immediately, then best-effort calls `POST /auth/logout` in the background. It does **not** clear the media master key; the Recovery Key remains on device across logout.

## Recovery Key and encryption

Every account/device uses a 32-byte media master key, presented to the user as a URL-safe Base64 Recovery Key. The user can generate a new key or import an existing one.

- Generated keys remain in a separately wrapped pending slot until the user confirms that the Recovery Key was saved.
- The stored master key is encrypted by a non-exportable Android Keystore AES-256-GCM wrapping key.
- The master key is never uploaded, logged or stored as plaintext.
- Recovery Key reveal requires biometric or device-credential authentication.
- Recovery Key screens set `FLAG_SECURE`, and clipboard entries are marked sensitive.

The master key is available after device unlock without a biometric prompt because unattended background backup must be able to encrypt new media. Biometrics protect revealing the key to a person, not routine cryptographic use by the app.

Media encryption uses libsodium XChaCha20-Poly1305. The version 1 format uses independently authenticated chunks with a maximum plaintext size of 4 MiB. Each encrypted chunk contains:

```text
version byte | 24-byte random nonce | ciphertext and authentication tag
```

Authenticated additional data binds the protocol version, media ID, blob type and chunk index. HKDF-SHA-256 derives separate keys for originals, thumbnails, private metadata and fingerprints. An authentication failure raises `IntegrityException` and returns no plaintext.

Deduplication fingerprints are account-private:

```text
HMAC(derived fingerprint key, SHA-256(plaintext media))
```

Hashing is streamed with bounded buffers; the backend does not receive a normal plaintext file hash.

## Local gallery

`MediaStoreScanner` queries image and video collections off the main thread and upserts them into Room. Records track local/cloud state, backup state, thumbnail state, timestamps, dimensions, duration and size.

Discovery happens through two complementary paths:

1. A `ContentObserver` on `MediaStore.Files` requests unique immediate work `Encrage.media.scan.now` while the app process is alive.
2. A six-hour periodic `ReconciliationWorker` (`Encrage.media.reconciliation`, one-hour flex) catches changes made while the app was stopped.

MediaStore notifications are treated as reconciliation hints, not as one-notification-per-file events. Full library access allows missing local items to be marked safely. Selected-media access is supported as a limited view and must not crash the app.

The Photos tab uses Room `PagingSource` -> `Pager` -> Compose `LazyVerticalGrid` (page size 90, initial load 180, prefetch distance 120). Paging placeholders are enabled. Date sections are not derived from loaded pages: `MediaDao.observeTimeline` returns only the capture times in paging order, and `PhotosViewModel.timeline` turns them into `DaySection(day, firstMediaIndex, count)` (calendar months when the grid has 5+ columns). The grid lays out each header followed by `count` cells mapped to paging indices, so headers are right before rows load and the layout never jumps. Tile keys are the Room `localId` once loaded. Local files are preferred over cloud copies. There is no `RemoteMediator`; paging is Room-only.

Local tiles go through `LocalThumbnail` -> `LocalThumbnailFetcher` (Coil) -> `ContentResolver.loadThumbnail` on Android 10+, which uses the platform thumbnail cache and is cancelled when a tile scrolls away. Older versions decode the URI with Coil.

The top bar collapses on scroll and becomes a contextual selection bar (clear, select all loaded, save, delete) while selecting. Filter chips (All / Photos / Videos and a backup-status menu) sit at the top of the grid. Pull to refresh rescans MediaStore, syncs all cloud pages and nudges the backup queue. A right-edge fast scroller shows the month while dragging. Pinch or the grid menu sets 2-6 columns (persisted in DataStore). Tile badges: none when backed up, a cloud when cloud-only, an upload arrow while waiting, a spinner while uploading, an error icon on failure.

Cloud-only thumbnails are fetched through the authenticated API, decrypted with the thumbnail key domain and retained only in a bounded in-memory bitmap cache. Opening a cloud-only photo fetches and authenticates the original encrypted chunks and decodes the original stream without persisting plaintext. Opening a cloud-only video gives ExoPlayer a random-access data source that decrypts one 4 MiB original chunk at a time, so playback and seeking do not require a complete plaintext file. The photo viewer caps the decoded bitmap edge at 2560 px (neighbouring pager pages can be in memory at once), applies the EXIF orientation read from the first decrypted chunk, and shows the cloud thumbnail until the original is ready. Originals are decrypted only for the settled page.

Long-pressing a tile enters selection mode; subsequent taps add or remove tiles. Selected cloud-only items can be downloaded individually or as a group into `DCIM/Encrage` through MediaStore. Decrypted download bytes stream directly to the pending MediaStore output and the Room row becomes `LOCAL_AND_CLOUD` after publication. Selected items can also be deleted: Android's system confirmation removes local MediaStore items on Android 11+, then `DELETE /media/{media_id}` removes cloud copies. Partial remote failures retain an accurate remaining local/cloud Room state and surface an error.

`MediaViewer` is a full-screen `HorizontalPager` over the same paging list: swipe between items, pinch or double-tap to zoom (pan while zoomed), swipe down to close, tap to toggle the chrome and immersive system bars. The top bar shows date, time, size and dimensions; the bottom bar shows backup state with Save (cloud-only) and Delete. Videos use Media3's Compose `ContentFrame` with custom play/pause and seek controls; only the settled page owns an `ExoPlayer`, neighbours show a poster.

## Automatic backup

New local records begin in `WAITING`. Reconciliation schedules unique work `Encrage.media.backup.upload` when automatic and background backup are enabled.

WorkManager constraints reflect the user settings:

- Wi-Fi/unmetered by default
- any connected network when mobile data is enabled, or when Retry/force runs
- optional charging requirement (ignored on force runs)
- `NOT_ROAMING` when mobile data is enabled but roaming is not

`BackupWorker` processes bounded batches (3 items per batch, up to 15 items per run) and chains another unique work request when items remain. Connectivity failures (and a Keystore that locks mid-run) return `UploadOutcome.Offline`: the item goes back to `WAITING`, the run stops and returns `Result.retry()` with 30 s exponential backoff, so an offline server never turns the queue into failures. Worker cancellation also requeues rather than failing the item. When `already_exists` (or completion) names a `mediaId` that another Room row already owns, `MediaDao.attachCloudCopy` merges the cloud-only twin into the local row instead of violating the unique index; a second local copy of identical content stays backed up without owning the cloud record. `MediaBackupUploader` handles one item at a time:

```text
fingerprint plaintext
    -> POST /media/uploads (create session)
    -> if already_exists: merge local record with cloud media and stop
    -> GET upload status for uploaded chunk indexes / thumbnail flag
    -> read one 4 MiB plaintext chunk
    -> encrypt and upload it
    -> persist uploaded indexes in Room
    -> generate a 512 px JPEG thumbnail
    -> encrypt and upload thumbnail
    -> complete session
    -> mark Room record BACKED_UP
```

Deduplication is driven by the create-upload `already_exists` response, not by calling `POST /media/fingerprints/check` (that Retrofit method is declared but unused). Uploads omit `private_metadata_ciphertext`.

The pipeline never creates a second full-size encrypted file and never loads an entire video into memory. Plaintext, derived keys and ciphertext buffers are wiped when practical. Upload-session rows and server status allow a killed or failed job to continue with missing chunks rather than restarting at byte zero.

## UI destinations

- **Photos** — local-first, paged, date-grouped gallery with filter chips, a swipeable viewer, long-press and per-day multi-selection, download and deletion.
- **Backup** — a status card (up to date / uploading / waiting for Wi-Fi or charging / server unreachable / failed with summarised reasons) with the matching action, counts, and described settings. The tab shows a badge when backup needs attention.
- **Settings** — account card and password change, storage with retry, Recovery Key reveal, server address, version, and logout with confirmation.

Back from Backup or Settings returns to Photos; back during selection clears it. Sign-in and action errors are mapped to plain language by `UserMessages`.

The Material theme uses accessible warm-neutral palettes for both light and system dark modes. Interactive primary colors contrast strongly with their content colors, surface levels are visibly separated, and the bottom navigation uses a neutral container with a high-contrast selected indicator. Gallery status badges use a dark scrim so white icons remain visible over both light and dark media.

The launcher icon is an adaptive icon (`mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml`): an off-white `ic_launcher_background` colour, an ink drop with a keyhole cut out (`drawable/ic_launcher_foreground.xml`), and a matching `ic_launcher_monochrome` layer for Android 13+ themed icons. The admin console uses the same mark (`Backend/app/console_static/icon.svg`).

The main UI starts a non-blocking cloud metadata sync and backup pass. `syncAllCloudPages` walks every cursor page (200 per page, at most 500 pages) from the newest item. Each remote item merges into the Room row that already owns its `mediaId`, otherwise the row with the same private fingerprint.

## Backend contract

Retrofit declares these endpoint groups. Actively used by the app today:

```text
POST   /auth/login
POST   /auth/refresh
POST   /auth/logout
POST   /auth/change-password

GET    /users/me/storage
GET    /media
GET    /media/{media_id}/thumbnail
GET    /media/{media_id}/chunks/{chunk_index}
DELETE /media/{media_id}
POST   /media/uploads
GET    /media/uploads/{upload_id}
PUT    /media/uploads/{upload_id}/chunks/{chunk_index}
PUT    /media/uploads/{upload_id}/thumbnail
POST   /media/uploads/{upload_id}/complete
```

Declared on `EncrageApi` but not called by app code yet:

```text
POST   /media/fingerprints/check
DELETE /media/uploads/{upload_id}
```

The API base URL must end with `/`. Debug defaults to `http://10.0.2.2:8000/` (the emulator's alias for the host machine) and can be overridden with `ENCRAGE_DEBUG_API_URL` in the user's Gradle properties. The release URL is deliberately `https://api.example.invalid/` until a production endpoint is configured. Users can override the endpoint from Settings or the sign-in screen; the normalized value is stored in DataStore and applied to all requests immediately. Changing it clears local session credentials so tokens are never carried to a different server. Plain HTTP endpoints are accepted only in debug builds. An address typed without a scheme gets `http://` in debug builds and `https://` in release builds. Release builds enable minify and resource shrinking.

## Current limitations

- Private EXIF/GPS metadata extraction and encrypted upload are not implemented (`private_metadata_ciphertext` is always omitted).
- Download All and migration transfers are not implemented (there is no Settings entry). Individual/multi-select cloud-only restore is implemented.
- Items deleted on the server by another device are not removed from Room by sync.
- Imported Recovery Keys are checked for encoding/length but are not yet validated against known account ciphertext.
- User-visible long-transfer notifications remain.
- The wrapping keys require an unlocked device, so backup only progresses while the phone is unlocked (locked runs retry later).

Do not work around these gaps by weakening encryption, writing decrypted cloud files to uncontrolled caches, loading whole videos into memory, or bypassing Room as the gallery source of truth.

## Build and test

Use Android Studio's bundled JDK 17 and Android SDK Platform 36:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Run device tests with an emulator or connected device:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Existing tests:

- Unit: `MediaKeyDerivationTest` (HKDF domain separation)
- Instrumented: `ChunkCipherInstrumentedTest`, `MediaDaoInstrumentedTest` (Room merge / backup status)

## Security invariants for future changes

- Never persist the password.
- Never send the master key or ordinary plaintext hashes to the backend.
- Never implement a replacement cipher; continue using a reviewed libsodium XChaCha20-Poly1305 binding.
- Never reuse a key/nonce pair.
- Preserve version, media ID, blob type and chunk index in authenticated data.
- Never ignore authentication failures or display their output.
- Keep file, network, hashing and encryption work off the UI thread.
- Keep upload/download memory bounded and resumable.
- Do not persist decrypted cloud thumbnails or videos in an uncontrolled disk cache.
- Keep Room as the gallery source of truth and preserve useful offline behavior.
- Do not log tokens, Recovery Keys, derived keys, filenames/GPS metadata or media plaintext.
