# Encrage Android

Native Android frontend foundation for a private, client-side encrypted photo/video cloud.

## Implemented in this slice

- Kotlin, Jetpack Compose Material 3, Navigation 3, Hilt, Coroutines/Flow.
- Offline-first Room database with indexed local/cloud media state, resumable upload-session state, cursor state, and a Paging 3 gallery source.
- Three-tab Photos / Backup / Settings shell.
- Date-grouped, stable-key, paged gallery (2–6 columns, pinch or menu to resize; month grouping at 5+ columns). Sections come from a lightweight timeline query so headers never shift while pages load. Local tiles use the platform thumbnail cache through a custom Coil fetcher.
- Swipeable full-screen viewer for local and cloud media: pinch/double-tap zoom with pan, swipe down to close, immersive mode, per-item Save/Delete, Compose Media3 video controls, EXIF-correct cloud originals.
- MediaStore image/video reconciliation with modern/legacy permission handling. Selected-media access is treated as limited access, not an error.
- A process-live ContentObserver plus unique immediate WorkManager scans and a six-hour periodic reconciliation worker.
- Login, secure refresh-token persistence, instant offline session restore, bearer injection, rotated refresh tokens. Startup restore and 401 refresh share one lock (`TokenRefresher`) so a rotated token is never spent twice.
- A 32-byte media master key generated from `SecureRandom`, or imported as a Recovery Key. It is wrapped by a non-exportable Android Keystore AES-256-GCM key before local storage.
- Biometric/device-credential gated Recovery Key reveal; the reveal uses `FLAG_SECURE`.
- Versioned 4 MiB chunk protocol using libsodium XChaCha20-Poly1305. AAD binds version, media ID, blob type, and chunk index. Originals, thumbnails, and private metadata use separate HKDF-SHA-256 subkeys.
- Streaming privacy-preserving fingerprint primitive: `HMAC(account fingerprint key, SHA-256(plaintext))` with bounded buffers.
- Resumable `BackupWorker` pipeline: fingerprint → `POST /media/uploads` → encrypt/upload 4 MiB chunks → encrypted 512 px JPEG thumbnail → complete. Room upload sessions track chunk indexes for resume.
- Cursor-based cloud metadata merge into Room across all pages; UI never waits directly on an API request. Pull to refresh rescans the phone and resyncs.
- Server-unreachable detection (`ServerReachability`): uploads pause and back off instead of marking items failed; the Photos and Backup screens explain the outage.
- Debug base URL configuration, release shrinking, backup exclusion rules, Room schema export, crypto tests, and Room transition/merge tests.

## Not implemented yet

The following later phases remain:

- Private EXIF/GPS metadata extraction/encryption (uploads currently omit `private_metadata_ciphertext`).
- Download All / new-phone migration (individual and multi-select restore to `DCIM/Encrage` exist).
- Local validation of an imported Recovery Key against known account ciphertext. Until that endpoint payload/validation record is defined, import validates encoding and length only.
- Upload throttling and transfer notifications.

## Backend assumptions

- Base URL ends in `/`.
- Login/refresh fields match the supplied snake_case contract.
- `GET /media` returns `{ "items": [...], "next_cursor": "..." | null }` with ISO-8601 `datetime_taken` and `plaintext_size_bytes`.
- `media_fingerprint` is stable per account and is returned with cloud metadata so local/cloud records can merge.
- Refresh rejection uses HTTP 400, 401, or 403; other failures preserve the local/offline session.

## Local configuration

Install Android Studio with Android SDK Platform 36 and JDK 17. The build uses Gradle 8.13 and AGP 8.13.2.

The debug API defaults to `http://10.0.2.2:8000/`, which the Android emulator maps to the host machine's localhost. Override it without editing source:

```properties
# ~/.gradle/gradle.properties (do not commit secrets)
ENCRAGE_DEBUG_API_URL=https://your-lan-host.example/
```

The endpoint can also be changed at runtime from Settings → API endpoint, or from the sign-in screen. Enter either an IP address with an optional port (for example, `192.0.2.20:8000`) or a full base URL. Endpoint changes are persisted and sign out the current session. Plain HTTP is supported only by debug builds.

The production placeholder is `https://api.example.invalid/`; replace `API_BASE_URL` for release builds before shipping. No API secrets belong in Gradle properties or the APK.

## Build and run

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Install `app/build/outputs/apk/debug/app-debug.apk`, or open the project in Android Studio and run the `app` configuration on Android 8.0+ (API 26+).

Instrumentation tests require an emulator/device:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Backup behaviour

1. MediaStore scan marks new local items `WAITING`.
2. With automatic backup enabled, signed in, and a Recovery Key configured, `BackupWorker` drains the queue.
3. Default WorkManager constraints require unmetered network unless “use mobile data” is enabled; charging-only is optional.
4. Backup tab **Retry** forces an immediate scan + upload pass.
5. Completed media appears under the backend `ENCRAGE_MEDIA_ROOT` as `{user_id}/media/{media_id}/`.

## Security notes

- Passwords are submitted once and never persisted.
- Refresh token and media master key use different Android Keystore aliases and ciphertext files under `noBackupFilesDir`.
- The master key is intentionally not biometric-gated for routine unwrap: reliable unattended background backup requires access after the device has been unlocked. Biometric/device credential is required for revealing it to the user.
- Decrypted cloud media is not persisted by this implementation. When cloud thumbnail support is added, disk caching must remain disabled or use an explicitly encrypted cache.
- XChaCha20-Poly1305 comes from libsodium through LazySodium. The app does not implement the cipher.
- Authentication failure throws `IntegrityException`, wipes the candidate plaintext buffer, and never returns it.
- Recovery Key material is never logged or sent to the backend. Android backup is disabled and data-extraction rules exclude local secrets/databases.
