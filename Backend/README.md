# Encrage encrypted media API

Encrage is a FastAPI + SQLite authenticated storage/indexing service for opaque, client-encrypted photos and videos. Android performs thumbnail generation, metadata extraction, XChaCha20-Poly1305 encryption, and decryption. The backend never receives a user's media encryption master key, never decodes media, and stores every uploaded chunk byte-for-byte.

## Setup

Python 3.11+ is required. FFmpeg, Pillow, and server-side cryptography packages are not required.

```powershell
py -3.12 -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
Copy-Item .env.example .env
$env:ENCRAGE_JWT_SECRET = '<at-least-32-random-bytes>'
python manage.py migrate
python manage.py create-user alice --quota-bytes 10737418240
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

OpenAPI documentation is served at `/docs`. Production traffic is rejected unless FastAPI sees an HTTPS request. Terminate TLS at a trusted reverse proxy, configure Uvicorn's trusted forwarded IPs narrowly, and persist the SQLite database and media root together in backups.

## Docker

`docker-compose.yml` runs two containers from one image. Both use restart policy `unless-stopped` and mount the same data directory:

| Container | Runs | Container port |
|---|---|---|
| `encrage-backend` | the user API (`app.main:app`) | 8000 |
| `encrage-console` | the admin web console (`app.console:app`) | 8001 |

Both read `./.env`, mount `ENCRAGE_DATA_DIR` at `/data`, and force the in-container paths to `/data/encrage.sqlite3` and `/data/media`. Deployment keys in `.env` (see `.env.example`):

| Key | Meaning | Default |
|---|---|---|
| `ENCRAGE_DATA_DIR` | Host directory holding the database and `media/` | `./data` |
| `ENCRAGE_API_PORT` | Host port for the API | 8000 |
| `ENCRAGE_CONSOLE_PORT` | Host port for the console | 8001 |
| `ENCRAGE_API_BIND`, `ENCRAGE_CONSOLE_BIND` | Host address each port listens on (for example a LAN IP) | `0.0.0.0` |
| `ENCRAGE_UID`, `ENCRAGE_GID` | Host user that owns the data directory | 10001 |

The image defaults to `ENCRAGE_ENV=production`, which makes the API and the console reject plain HTTP. Set `ENCRAGE_ENV=development` in `.env` only when no TLS proxy sits in front of them on a trusted LAN.

For a NAS reachable over SSH, `deploy/encrage-nas.ps1` controls the deployment from Windows. Copy `deploy/nas.example.env` to `deploy/nas.local.env`, which is gitignored, and fill in the host, SSH user, key and folder. The script keeps the compose project in that folder (default `~/encrage`). Then run:

```powershell
.\deploy\encrage-nas.ps1 status                    # container state, health and URLs
.\deploy\encrage-nas.ps1 stop                      # deactivate both (stays stopped across reboots)
.\deploy\encrage-nas.ps1 start                     # activate both
.\deploy\encrage-nas.ps1 stop -Service console     # just the console (-Service api|console)
.\deploy\encrage-nas.ps1 logs -Service api         # follow logs
.\deploy\encrage-nas.ps1 console-admin -Name <n>   # create a console admin or reset its password
.\deploy\encrage-nas.ps1 deploy                    # upload current code, rebuild, restart (never touches .env or data/)
```

Parameters (`-HostName`, `-User`, `-KeyPath`, `-RemoteDir`) override the `ENCRAGE_NAS_HOST`, `ENCRAGE_NAS_USER`, `ENCRAGE_NAS_KEY` and `ENCRAGE_NAS_DIR` environment variables, which override `nas.local.env`. The NAS's SFTP may be chrooted, so the script uploads with `scp -O`. After changing ports or `ENCRAGE_DATA_DIR` in the NAS's `.env`, run `start` to recreate the containers.

Management commands run inside a container. Run these from `~/encrage` on the NAS:

```bash
docker compose exec encrage-backend python manage.py create-user <name> --quota-bytes <n>
docker compose exec encrage-backend python manage.py reset-password <name>
docker compose exec encrage-console python manage.py set-console-admin <name>
docker compose exec encrage-console python manage.py remove-console-admin <name>
```

`reset-password` prompts twice for a new password (at least 12 characters), replaces the hash, and revokes all of that user's sessions and refresh tokens, so every device must sign in again. The media master key does not depend on the password, so a reset never affects decryption. Users who know their current password can instead change it in the app (`POST /auth/change-password`), which keeps the calling session signed in.

## Admin console

The console is a separate FastAPI app (`app/console.py`, static page in `app/console_static/`) on its own port. It is never mounted on the user API. It offers:

- **Overview:** health of the API (probed over the compose network), disk, encrypted storage, the database (size, schema version, on-demand `PRAGMA quick_check`), upload and deletion queues, and warnings.
- **Recent usage:** per-day charts and totals over 7, 30 or 90 days, top uploaders, and the latest activity.
- **Users:** add users, change quotas (never below current usage), reset passwords, sign out all devices, disable or enable accounts, and delete accounts. Deleting requires typing the username, then removes the account's rows and all its files through `file_delete_queue`.
- **Activity:** a filterable log of sign-ins, failed sign-ins, uploads, deletions and every admin action.

Console admins are separate from app users and exist only in `console_admins`. Create them from the CLI with `set-console-admin`; the web page cannot create them. Sign-in sets an opaque session cookie, stored only as SHA-256 in `console_sessions`. The cookie is HttpOnly and SameSite=Strict, Secure over HTTPS, and expires after `ENCRAGE_CONSOLE_SESSION_HOURS` (12). After 5 failed attempts from one address in 15 minutes, sign-in is locked for that address, and after 30 failures in total it is locked for everyone. State-changing requests must send `X-Encrage-Console: 1` and pass a same-origin `Origin` check. Pages are served with a strict Content-Security-Policy and load no third-party code.

Usage history works as follows:

- **Uploads and sign-ins per day** come from `media.created_at` and `sessions.created_at`, so they cover data from before the console existed.
- **Deletions, failed sign-ins and admin actions** come from `activity_events` (schema v4), which starts empty. Events older than `ENCRAGE_ACTIVITY_RETENTION_DAYS` (90) are pruned at startup, hourly by the console, and by `cleanup-uploads`.
- **What events contain:** event type, time, account, actor, an opaque byte count, the client address, and for uploads and deletions only whether the item is an image or a video. They never hold media fingerprints, filenames, metadata, tokens or passwords. A failed sign-in for an unknown username stores no username, because the user may have typed a password into that field.

## Authentication

`POST /auth/login` accepts JSON:

```json
{"username":"alice","password":"correct horse battery staple"}
```

It returns a one-hour JWT access token and a long-lived opaque refresh token. Only the SHA-256 hash of the high-entropy refresh token is stored. `POST /auth/refresh` accepts `{"refresh_token":"..."}`, rotates the token, and invalidates its predecessor. Reuse of a rotated token revokes its whole session. `POST /auth/logout` requires the access bearer token and revokes that session and all its refresh tokens. `POST /auth/change-password` requires the access bearer token plus the current password, sets a new password of at least 12 characters, and revokes every other session for that user while leaving the calling session intact.

Every non-authentication endpoint requires `Authorization: Bearer <access_token>`. Ownership always comes from the token's `user_id`; no route accepts a username or user ID for scoping.

## Version-1 client encryption contract

- Target plaintext chunk size: 4 MiB.
- The configured default accepts encrypted request bodies up to 5 MiB, leaving room for the XChaCha20-Poly1305 tag and a client envelope.
- Every original-media chunk is independently authenticated.
- The thumbnail is generated, encoded, and encrypted by Android.
- Associated data should bind protocol version, `media_id`, blob type, and chunk index.
- The server stores request bodies unchanged and returns them as `application/octet-stream`.
- `mime_type`, dimensions, duration, and plaintext size are untrusted indexing metadata, not proof of content.
- Original filenames, GPS, location names, and camera/private fields belong inside `private_metadata_ciphertext`.
- `media_fingerprint` must be a privacy-preserving keyed value generated by the client; plaintext SHA-256 must not be sent.

The `media_id` is allocated by `POST /media/uploads`, so Android can bind it into associated data before encrypting/uploading chunks.

## Upload sequence

1. `POST /media/uploads` with metadata, fingerprint, protocol version, and `total_chunks`.
2. If `already_exists` is true, stop. If `upload_in_progress` is true, query and resume that `upload_id`.
3. `GET /media/uploads/{upload_id}` to obtain uploaded chunk indexes.
4. `PUT /media/uploads/{upload_id}/chunks/{chunk_index}` once per encrypted chunk.
5. `PUT /media/uploads/{upload_id}/thumbnail` with the encrypted thumbnail.
6. `POST /media/uploads/{upload_id}/complete`.

Chunk and thumbnail PUT bodies are raw binary, not multipart. Repeating the same ciphertext is idempotent. Repeating an index with different ciphertext safely replaces the incomplete object and adjusts its quota reservation.

Uploads reserve quota based on actual encrypted bytes. Completion re-stats every object, atomically moves the server-generated upload directory into the completed-media area, inserts completed metadata/chunk rows, and moves the exact stored total into `users.space_used_bytes`. Incomplete media never appears in `GET /media`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/auth/login` | Password login; returns access and refresh tokens |
| `POST` | `/auth/refresh` | Rotate an opaque refresh token |
| `POST` | `/auth/logout` | Revoke the authenticated session |
| `POST` | `/auth/change-password` | Change password with access token + current password |
| `GET` | `/users/me` | Current user identity |
| `GET` | `/users/me/storage` | Allocated, used, and available committed storage |
| `POST` | `/media/fingerprints/check` | Batch-check completed fingerprints |
| `POST` | `/media/uploads` | Create or resume a deduplicated upload session |
| `GET` | `/media/uploads/{upload_id}` | Uploaded indexes, thumbnail status, and state |
| `PUT` | `/media/uploads/{upload_id}/chunks/{chunk_index}` | Stream one encrypted original chunk |
| `PUT` | `/media/uploads/{upload_id}/thumbnail` | Stream the encrypted thumbnail |
| `POST` | `/media/uploads/{upload_id}/complete` | Validate and atomically finalize an upload |
| `DELETE` | `/media/uploads/{upload_id}` | Cancel an incomplete upload |
| `GET` | `/media` | Cursor-paginated completed metadata |
| `GET` | `/media/{media_id}` | One completed metadata record |
| `GET` | `/media/{media_id}/thumbnail` | Stream unchanged encrypted thumbnail bytes |
| `GET` | `/media/{media_id}/chunks/{chunk_index}` | Stream unchanged encrypted chunk bytes |
| `DELETE` | `/media/{media_id}` | Delete media and correct quota accounting |

`GET /media` supports `limit`, `cursor`, `media_type`, `mime_type`, `datetime_taken_from`, `datetime_taken_to`, `size_min`, `size_max`, `sort_by`, and `sort_order`. Its default is `datetime_taken DESC`.

## Migrations and legacy media

Schema migrations run automatically at startup and can be run explicitly with:

```powershell
python manage.py migrate
```

The migration never drops old rows or files. The previous monolithic `media` table is renamed to `legacy_media_v1`. Rows whose files exist are exposed through the new model with:

- a deterministic `legacy_...` fingerprint;
- `encryption_version = 0`;
- one legacy chunk;
- no plaintext original filename, GPS, or private metadata copied into API-visible fields.

Missing legacy files are recorded in `legacy_migration_issues` and their old rows remain untouched. The old `auth_sessions` table is retained for audit/migration safety but is not accepted by the new authentication system; users must log in again.

Important: the immediately preceding backend could wrap uploaded ciphertext in a server AES-GCM container. This release has no server media key and does not unwrap or reinterpret those legacy bytes. Version-0 downloads are the exact bytes currently on disk and are not version-1 Android ciphertext. If such production data exists, recover/export it with the prior trusted deployment in an offline migration before retiring its old key. New version-1 uploads have no server cryptographic wrapper.

## Cleanup and recovery

Incomplete sessions older than `ENCRAGE_UPLOAD_TTL_HOURS` are removed at startup. Run this command periodically as a scheduled job as well:

```powershell
python manage.py cleanup-uploads
```

Successful status checks and binary uploads refresh the session's activity timestamp. Android should check upload status while a long-running upload is active; the default seven-day TTL is deliberately much longer than an ordinary mobile upload interruption.

Cancellation, cleanup, replacement, and completed-media deletion use a durable `file_delete_queue`. Database/quota changes commit first; filesystem removal is retried after interruption. Finalization uses an atomic directory rename and startup recovery repairs a rename interrupted before database commit.

## Tests

```powershell
pytest
```

Tests cover authentication and refresh rotation, session logout, ownership isolation, resumable and idempotent chunks, exact byte round trips, incomplete completion, deduplication, quota races, deletion, abandoned-upload cleanup, cursor pagination, and legacy migration.
