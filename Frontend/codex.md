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
- Valid refresh token: obtain a new access token and enter the app.
- No network or a transient server error: preserve the local session and remain usable offline.
- HTTP 400, 401 or 403 from refresh: treat the refresh credential as invalid and clear the authentication session.
- Signed in without a confirmed master key (or with a pending generated key): show `EncryptionSetupScreen` before the main shell.

Passwords are sent only to `POST /auth/login` and the authenticated `POST /auth/change-password`; they are never persisted. Settings validates that a replacement password is at least 12 characters, differs from the current password and matches its confirmation before requesting the change. A successful change keeps the current session, while the backend revokes the user's other sessions. Access tokens remain in memory. Refresh tokens are encrypted with an AES-256-GCM key held by Android Keystore before being written under `noBackupFilesDir`.

`BearerInterceptor` adds access tokens to normal requests. `RefreshAuthenticator` serializes refresh attempts so concurrent 401 responses cause one refresh rather than a refresh storm.

Login and startup restore persist the rotated refresh token before exposing the new access token. The 401 `RefreshAuthenticator` path currently sets the in-memory access token first, then persists the rotated refresh token.

Logout best-effort calls `POST /auth/logout`, then clears tokens and username. It does **not** clear the media master key; the Recovery Key remains on device across logout.

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

The Photos tab uses Room `PagingSource` -> `Pager` -> Compose `LazyVerticalGrid` (page size 90, initial load 180, prefetch distance 120). Paging placeholders are enabled, so the fixed-size grid can lay out distant unloaded cells and fetch their records/thumbnails as they approach the viewport. Items are sorted newest first and grouped by local date. Local files are preferred over cloud copies. There is no `RemoteMediator`; paging is Room-only.

The filter icon opens a left-side navigation drawer (up to 360 dp wide) with Media Type and Backup Status dropdowns. The grid no longer dedicates two rows to filter chips.

Cloud-only thumbnails are fetched through the authenticated API, decrypted with the thumbnail key domain and retained only in a bounded in-memory bitmap cache. Opening a cloud-only photo fetches and authenticates the original encrypted chunks and decodes the original stream without persisting plaintext. Opening a cloud-only video gives ExoPlayer a random-access data source that decrypts one 4 MiB original chunk at a time, so playback and seeking do not require a complete plaintext file. The photo viewer caps the decoded bitmap edge at 4096 px to keep display memory bounded while sourcing pixels from the original rather than the thumbnail.

Long-pressing a tile enters selection mode; subsequent taps add or remove tiles. Selected cloud-only items can be downloaded individually or as a group into `DCIM/Encrage` through MediaStore. Decrypted download bytes stream directly to the pending MediaStore output and the Room row becomes `LOCAL_AND_CLOUD` after publication. Selected items can also be deleted: Android's system confirmation removes local MediaStore items on Android 11+, then `DELETE /media/{media_id}` removes cloud copies. Partial remote failures retain an accurate remaining local/cloud Room state and surface an error.

Local and cloud videos use the same deliberately reduced player controls: play/pause in the center and the normal progress/timing row. Previous/next, rewind, fast-forward and shuffle controls are hidden, and the player is inset above the system navigation bar.

## Automatic backup

New local records begin in `WAITING`. Reconciliation schedules unique work `Encrage.media.backup.upload` when automatic and background backup are enabled.

WorkManager constraints reflect the user settings:

- Wi-Fi/unmetered by default
- any connected network when mobile data is enabled, or when Retry/force runs
- optional charging requirement (ignored on force runs)
- a “use data while roaming” preference exists in the Backup UI/DataStore but is **not** applied to WorkManager constraints today

`BackupWorker` processes bounded batches (3 items per batch, up to 15 items per run) and chains another unique work request when items remain. `MediaBackupUploader` handles one item at a time:

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

- **Photos** — local-first, paged, date-grouped gallery with a filter drawer, cloud-original viewing, long-press multi-selection, download and deletion.
- **Backup** — queue status, failures, retry action and backup/network constraints.
- **Settings** — account and password change, cloud storage usage, Download All placeholder, Recovery Key reveal, editable API endpoint, About and logout.

The Material theme uses accessible warm-neutral palettes for both light and system dark modes. Interactive primary colors contrast strongly with their content colors, surface levels are visibly separated, and the bottom navigation uses a neutral container with a high-contrast selected indicator. Gallery status badges use a dark scrim so white icons remain visible over both light and dark media.

The main UI starts a non-blocking cloud metadata sync and backup pass. Cloud metadata is merged into Room by the private fingerprint. Sync always requests the first cursor page (`reset = true`); further pages are not loaded automatically.

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

The API base URL must end with `/`. Debug defaults to `http://10.0.2.2:8000/` (the emulator's alias for the host machine) and can be overridden with `ENCRAGE_DEBUG_API_URL` in the user's Gradle properties. The release URL is deliberately `https://api.example.invalid/` until a production endpoint is configured. Users can override the endpoint from Settings or the sign-in screen; the normalized value is stored in DataStore and applied to all requests immediately. Changing it clears local session credentials so tokens are never carried to a different server. Plain HTTP endpoints are accepted only in debug builds. Release builds enable minify and resource shrinking.

## Current limitations

- Private EXIF/GPS metadata extraction and encrypted upload are not implemented (`private_metadata_ciphertext` is always omitted).
- Only the initial cloud cursor page is synchronized automatically (and always with `reset = true` from the main shell).
- Download All and migration transfers are not implemented. Individual/multi-select cloud-only restore is implemented.
- Imported Recovery Keys are checked for encoding/length but are not yet validated against known account ciphertext.
- Cloud deletion/info UI, richer transfer errors and user-visible long-transfer notifications remain.
- Backup “roaming” preference is stored in the UI but not enforced in WorkManager network constraints.

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
