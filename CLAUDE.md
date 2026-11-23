# CLAUDE.md

Encrage is a client-side encrypted photo and video cloud. The Android app encrypts media on the device. The FastAPI backend stores opaque ciphertext and never sees plaintext or the media master key. **Every change must keep that property.**

| Path | What it is |
|---|---|
| `Backend/` | FastAPI + SQLite API (Python 3.11+, developed on 3.12). Flat module, no ORM. |
| `Frontend/` | Native Android app `com.encrage.vault` (Kotlin, Compose, Hilt, Room, WorkManager). |

The platform is Windows and the commands below are PowerShell. The git repo root is this folder.

## Commands

### Backend (run from `Backend/`)

```powershell
.venv\Scripts\Activate.ps1            # venv already exists locally
pip install -r requirements-dev.txt
python manage.py migrate              # also runs automatically at app startup
python manage.py create-user <name> --quota-bytes <n>   # prompts for password (>=12 chars)
python manage.py reset-password <name>                  # prompts for new password; revokes all sessions
python manage.py set-console-admin <name>               # create/reset a web console admin (not an app user)
python manage.py cleanup-uploads      # expire stale uploads + retry deferred deletes
uvicorn app.main:app --host 127.0.0.1 --port 8000       # OpenAPI at /docs
uvicorn app.console:app --host 127.0.0.1 --port 8001    # admin web console
pytest                                # all tests
pytest tests/test_api.py::test_quota_reservations_are_concurrency_safe   # single test
```

- `ENCRAGE_JWT_SECRET` must be at least 32 bytes or `get_settings()` raises. `.env` is loaded through `python-dotenv`. `tests/conftest.py` sets a dummy secret.
- Production runs in Docker on a NAS reached over SSH. It runs two containers from one image: `encrage-backend` (API) and `encrage-console` (admin console). The host, SSH user, key and folder live in the gitignored `Backend/deploy/nas.local.env`. **The repo is public**, so never commit real hosts, usernames, key names or ports, and keep them in that file. Ports and the data directory come from `ENCRAGE_API_PORT`, `ENCRAGE_CONSOLE_PORT` and `ENCRAGE_DATA_DIR` in the NAS's `.env`. Control it with `.\deploy\encrage-nas.ps1 start|stop|restart|status|logs|deploy|console-admin [-Service api|console]`. The NAS's SFTP is chrooted, so `scp` needs `-O`. Windows PowerShell 5.1 strips double quotes from native arguments, so the script sends remote commands base64-encoded. See the Docker section of `Backend/README.md`.
- If pytest fails with `PermissionError` on `...\Temp\pytest-of-*`, run `pytest --basetemp=.tmp/pytest -p no:cacheprovider`. `.tmp/` is gitignored.

### Frontend (run from `Frontend/`)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # JDK 17
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest                              # JVM unit tests
.\gradlew.bat :app:testDebugUnitTest --tests "com.encrage.vault.crypto.MediaKeyDerivationTest"
.\gradlew.bat :app:compileDebugAndroidTestKotlin                  # compile-check instrumented tests
.\gradlew.bat :app:connectedDebugAndroidTest                      # needs emulator/device
```

- Build stack: Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21, KSP, compile/target SDK 36, minSdk 26.
- The debug API URL defaults to `http://10.0.2.2:8000/` (the emulator's alias for the host). Override it with `ENCRAGE_DEBUG_API_URL` in `~/.gradle/gradle.properties`. The release URL is the placeholder `https://api.example.invalid/`.
- Unit tests live in `app/src/test` (`MediaKeyDerivationTest`, `ApiEndpointTest`, `ColorContrastTest`, `FormatTest`). Instrumented tests live in `app/src/androidTest` (`ChunkCipherInstrumentedTest`, `MediaDaoInstrumentedTest`). `ChunkCipher` needs libsodium, so its tests are instrumented only.

## Backend architecture

- `app/main.py` holds `create_app(settings)`, a factory that defines **all routes inline** as closures over `database` and `resolved_settings`. It also holds the module-level helpers `process_delete_queue`, `cleanup_abandoned_uploads` and `recover_interrupted_finalizations`, which `manage.py` reuses. `app = create_app()` sits at the bottom. Tests build their own app with `create_app(make_settings(tmp_path))`.
- `app/database.py` uses raw `sqlite3`: WAL mode, `synchronous=FULL`, foreign keys on. Use `database.transaction(immediate=True)` for any write that checks state before acting (quota, sessions). `CURRENT_SCHEMA` is idempotent `CREATE ... IF NOT EXISTS`. Migrations are ad-hoc steps in `Database.initialize()` (column adds, renaming the legacy `media` table to `legacy_media_v1`), stamped with `CURRENT_SCHEMA_VERSION` (currently 4; v4 added `activity_events`, `console_admins` and `console_sessions`). Migrations must never drop rows or files.
- `app/storage.py`: every on-disk path goes through `safe_path(root, relative)`, which rejects absolute paths and escapes from the media root. Upload objects go to `{user_id}/.uploads/{upload_id}/...`. On completion the whole directory is atomically renamed to `{user_id}/media/{media_id}/`.
- `app/security.py` covers Argon2id passwords, HS256 access JWTs, and opaque refresh tokens stored only as SHA-256. Refresh rotates the token, and reusing a rotated token revokes the whole session.
- `app/schemas.py`: request models extend `StrictModel` (extra fields are forbidden).
- Errors are always `{"error": {"code", "message", "details"}}`. Raise them with `api_error(status, code, message)`.
- Ownership always comes from the token's `user_id`. No route **on the user API** accepts a user ID or username for scoping. Keep it that way.
- `app/console.py` is a separate admin app (own port and container, never mounted on the user API) that serves `app/console_static/`. It authenticates `console_admins` with a SHA-256-stored session cookie (HttpOnly, SameSite=Strict) and needs the `X-Encrage-Console: 1` header plus a same-origin `Origin` on writes. Its auth dependency lives at module level, because with `from __future__ import annotations` FastAPI cannot resolve an `Annotated` alias defined inside the factory. Cross-account operations live in `app/admin.py`, which both the console and `manage.py` call. Never import them into the user API.
- `app/activity.py`: `record_event(connection, ...)` writes to `activity_events` inside the caller's transaction. Events hold only account-level facts and opaque byte counts. Never add fingerprints, filenames, MIME details, tokens or passwords. Retention is `ENCRAGE_ACTIVITY_RETENTION_DAYS` (90).
- Quota is reserved from actual encrypted bytes during upload and moved into `users.space_used_bytes` on completion. File removals go through the durable `file_delete_queue`: the database commits first and the filesystem catches up after.
- When `ENCRAGE_ENV=production`, the API rejects non-HTTPS requests. TLS terminates at a reverse proxy.

## Frontend architecture

Data flows Compose UI → ViewModels (`ui/AppViewModels.kt`) → repositories → Room + MediaStore + Retrofit. **Room is the only source the gallery renders.** The UI never waits on a network call.

| Package | Contents |
|---|---|
| `crypto/` | `ChunkCipher` (libsodium XChaCha20-Poly1305 via LazySodium), `MediaKeyDerivation` (HKDF-SHA-256 + `ChunkProtocolV1`), `MasterKeyManager`, `SecretStore` (Keystore-wrapped files under `noBackupFilesDir`), `MediaFingerprintGenerator` |
| `backup/` | `BackupScheduler` (which **also contains `BackupWorker`**), `MediaBackupUploader` (one item's resumable upload), `ReconciliationWorker` (6-hour periodic) |
| `media/` | `MediaStoreScanner` (**also contains `ThumbnailGenerator`**), the ContentObserver registrar, `CloudThumbnailLoader` (in-memory cache only), `CloudOriginalMedia` (cloud photo stream, ExoPlayer chunked `DataSource`, restore to `DCIM/Encrage`) |
| `data/local/` | Room `EncrageDatabase`: version 1 with exported schema in `app/schemas/`. There are no migrations yet, so add a `Migration` when you change entities. |
| `data/remote/` | `EncrageApi` (Retrofit), `SessionRepository`, `AuthNetworking` (`BearerInterceptor`, single-flight `RefreshAuthenticator`), `ApiEndpoint` (runtime base URL; must end in `/`; HTTP is allowed only in debug) |
| `di/AppModule.kt` | Hilt providers |

## Wire contract (shared by both sides)

Changing any of these breaks existing ciphertext or the other side, so update both together:

- Chunk format v1: `version(1) | nonce(24) | ciphertext+tag(16)`, with 4 MiB plaintext chunks. The server accepts bodies up to `ENCRAGE_MAX_CHUNK_BYTES` (5 MiB).
- AAD (`ChunkProtocolV1.aad`): `version | u16 len + media_id | u8 len + blob type | i32 chunk_index`. Blob types are `original`, `thumbnail` and `private-metadata`.
- HKDF salt is `encrage-hkdf-salt-v1` and info is `encrage:v1:<domain>:<media_id>`. The fingerprint key uses media ID `account`.
- `media_fingerprint` = `HMAC(fingerprint key, SHA-256(plaintext))`. Never send a plain SHA-256.
- `media_id` comes from `POST /media/uploads` before encryption, because it is bound into the AAD.
- Upload order: create → status → PUT chunks (raw `application/octet-stream`, not multipart) → PUT thumbnail → complete.
- The backend accepts only `encryption_version` 1 (`SUPPORTED_ENCRYPTION_VERSIONS`). Version 0 is migrated legacy data that the v1 decryptor must not touch.
- JSON is snake_case. Refresh responses of 400, 401 or 403 make the client clear its session. Any other failure keeps the offline session.

## Security invariants (do not weaken)

- The server never receives the master key, a plaintext hash, original filenames, GPS or EXIF. Private fields belong only in `private_metadata_ciphertext`, which the client does not send yet.
- Never persist passwords. Access tokens stay in memory. Refresh tokens and the master key stay Keystore-wrapped.
- Never write your own cipher, reuse a nonce, ignore an authentication failure (`IntegrityException`) or show unauthenticated plaintext.
- Never write decrypted cloud media to a disk cache. Keep memory bounded: process one chunk at a time and never load a whole video.
- Keep file, network and crypto work off the main thread.
- Never log tokens, keys, Recovery Keys, filenames, GPS or plaintext. OkHttp logging is `BASIC` in debug only.

## Docs and their accuracy

- `Backend/README.md` and `Backend/codex.md` (the client contract) are accurate.
- `Frontend/codex.md` is the most current description of the Android app.
- `Frontend/README.md` and `Frontend/codex.md` were brought up to date with the UI/robustness pass (Nov 2026).
- Gaps that are real: no private EXIF/GPS upload, no Download All (no UI entry), imported Recovery Keys are not validated against account ciphertext, server-side deletions from other devices are not pruned from Room, and `checkFingerprints` and cancel-upload are declared but unused.
- Keystore wrapping keys use `setUnlockedDeviceRequired`, so decrypting secrets fails while the phone is locked. Check secret *presence* (`SecretStore.contains`, `MasterKeyManager.hasKey`) for routing decisions; never treat a decrypt failure as "no key" or "signed out".
- All refresh-token spending goes through `TokenRefresher` (one lock). Rotated refresh tokens revoke the session if reused, so never call `AuthApi.refresh` directly.
- When you change behaviour, update the matching README or codex.md as well.
