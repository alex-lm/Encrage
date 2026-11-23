# Encrage

A private, self-hosted photo and video backup with client-side encryption.

The Android app encrypts every photo, video and thumbnail on the phone before it leaves the device. The server stores opaque ciphertext and account metadata only. It never receives the media master key, a plaintext hash, filenames, GPS or EXIF, and it never decodes media.

## Repository layout

| Path | What it is |
|---|---|
| [`Backend/`](Backend/) | FastAPI + SQLite. Two apps from one codebase: the **user API** the phone talks to, and a separate **admin web console**. Ships as a Docker Compose deployment. |
| [`Frontend/`](Frontend/) | Native Android app `com.encrage.vault` (Kotlin, Jetpack Compose, Hilt, Room, WorkManager). |

## How it works

```text
 Android phone                                   Encrage server
 ─────────────                                   ──────────────
 MediaStore ──► Room (gallery source of truth)
                  │
 Recovery Key ─► HKDF subkeys                    User API  (port 8000)
  (Keystore-     │                                ├─ auth, quotas, sessions
   wrapped)      ▼                                ├─ resumable uploads
             XChaCha20-Poly1305, 4 MiB chunks ──► └─ stores ciphertext byte-for-byte
             + encrypted 512 px thumbnail            in {user_id}/media/{media_id}/
                                                  Admin console (port 8001)
 Cloud-only items ◄── decrypt in memory only ◄──   accounts, health, usage, activity
```

1. The app scans the phone's photos and videos into a local Room database. The gallery always renders from Room, so it works offline and never waits on the network.
2. A 32-byte media master key (the **Recovery Key**) is generated or imported, wrapped by Android Keystore, and never uploaded.
3. Each item is fingerprinted privately (`HMAC(key, SHA-256(plaintext))`), split into 4 MiB chunks, encrypted with XChaCha20-Poly1305 and uploaded through a resumable session. Authenticated data binds protocol version, media ID, blob type and chunk index.
4. The server authenticates the user, enforces the quota, deduplicates by fingerprint and stores the encrypted chunks.
5. Cloud-only items are decrypted on demand in memory (thumbnails, full photos, streamed video) and can be restored to the phone.

## What you get

**Android app**
- Offline-first gallery with date sections, filter chips (type and backup status), pinch to resize (2–6 per row), pull to refresh and a fast scroller.
- Swipeable full-screen viewer for local and cloud items: zoom and pan, swipe down to close, immersive mode, streamed playback of encrypted video, save to phone, delete.
- Automatic background backup with Wi‑Fi, mobile data, roaming and charging rules. It pauses and retries on its own when the server is unreachable.
- A Backup screen that explains what is happening and why items failed, plus Settings for the account, storage, Recovery Key, server address and sign-out.

**Server**
- User API: login with rotating refresh tokens, resumable chunked uploads, quotas, cursor-paged media listing, deduplication, durable deletion queue.
- Admin console on its own port: health and storage overview, usage charts, user management (create, quota, reset password, sign out, disable, delete) and an activity log. It sees account metadata and sizes only, never media or keys.

## Quick start

### Server with Docker (recommended)

```bash
cd Backend
cp .env.example .env          # set ENCRAGE_JWT_SECRET to at least 32 random bytes
# The containers run as ENCRAGE_UID:ENCRAGE_GID, which must own the data directory.
# Either set both to your own `id -u` / `id -g` in .env, or keep 10001 and chown:
mkdir -p data && sudo chown 10001:10001 data
docker compose up -d --build  # API on :8000, console on :8001
docker compose exec encrage-backend python manage.py create-user alice --quota-bytes 10737418240
docker compose exec encrage-console python manage.py set-console-admin admin   # prompts for a password
```

Then sign in to the console at `http://<server>:8001/`.

`.env` controls the deployment:

| Key | Sets |
|---|---|
| `ENCRAGE_API_PORT`, `ENCRAGE_CONSOLE_PORT` | Host ports for the API and the console |
| `ENCRAGE_API_BIND`, `ENCRAGE_CONSOLE_BIND` | Host addresses they listen on |
| `ENCRAGE_DATA_DIR` | Where the database and encrypted media live on the host |

`.env.example` sets `ENCRAGE_ENV=development`, which accepts plain HTTP and is meant for a trusted LAN. For anything reachable from the internet, set `ENCRAGE_ENV=production`, which makes both apps reject non-HTTPS requests, and put a TLS reverse proxy in front of both. See [`Backend/README.md`](Backend/README.md) for the full reference.

### Running it on the NAS

The production server runs these two containers on a NAS. From Windows, [`Backend/deploy/encrage-nas.ps1`](Backend/deploy/encrage-nas.ps1) turns them on and off and deploys new code over SSH:

```powershell
cd Backend
.\deploy\encrage-nas.ps1 status                    # state, health and URLs
.\deploy\encrage-nas.ps1 stop                      # stop the API and console (stays off across reboots)
.\deploy\encrage-nas.ps1 start
.\deploy\encrage-nas.ps1 stop -Service console     # only the console (-Service api|console)
.\deploy\encrage-nas.ps1 deploy                    # upload code, rebuild, restart; never touches .env or data
```

The host, user, SSH key and folder are parameters, or come from `ENCRAGE_NAS_HOST`, `ENCRAGE_NAS_USER`, `ENCRAGE_NAS_KEY` and `ENCRAGE_NAS_DIR`.

### Server for local development (Windows)

```powershell
cd Backend
py -3.12 -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
Copy-Item .env.example .env     # set ENCRAGE_JWT_SECRET
python manage.py migrate
python manage.py create-user alice --quota-bytes 10737418240
python manage.py set-console-admin admin
uvicorn app.main:app --host 0.0.0.0 --port 8000     # OpenAPI at /docs
uvicorn app.console:app --host 127.0.0.1 --port 8001   # admin console (second terminal)
pytest
```

### Android app

Install Android Studio (SDK Platform 36, JDK 17), then:

```powershell
cd Frontend
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug          # APK in app/build/outputs/apk/debug/
.\gradlew.bat :app:testDebugUnitTest
```

On first launch, open **Server** on the sign-in screen and enter your server's address (for example `192.168.1.20:8000`). The debug build's default comes from `ENCRAGE_DEBUG_API_URL` in `~/.gradle/gradle.properties` and otherwise is `http://10.0.2.2:8000/` (the emulator's alias for your PC). Debug builds accept HTTP; release builds require HTTPS.

## Security model

- The server never holds the master key or any plaintext. Losing the Recovery Key, with no other device holding it, means the backups can't be decrypted. Nobody can reset it, including the server admin.
- Passwords are sent only to sign in or change the password, and are never stored on the phone. Access tokens live in memory. Refresh tokens and the master key are wrapped by Android Keystore keys that require an unlocked device.
- Refresh tokens rotate, and reusing an old one revokes the whole session. The app spends them through a single lock so this can't happen by accident.
- Viewing the Recovery Key needs biometric or device-credential confirmation, and the screen is hidden from screenshots.
- Decrypted cloud media is never written to a disk cache. Memory stays bounded at one chunk at a time, and whole videos are never loaded.
- Production traffic must use HTTPS (`ENCRAGE_ENV=production`).
- The admin console is a separate app on its own port, and its routes don't exist on the API the phone uses. Console admins are separate from app users and can only be created from the server's command line. Sessions use HttpOnly, SameSite=Strict cookies. Sign-in is rate-limited, and changes require a same-origin request header.
- The console's activity log records account-level events and byte counts only. It never stores filenames, fingerprints, metadata, tokens or passwords, and it is pruned after 90 days.

## Known gaps

- Private EXIF/GPS metadata is not uploaded yet (the encrypted field exists in the protocol).
- There is no "Download all" for moving to a new phone. Individual and multi-select restore work.
- An imported Recovery Key is checked for format only, not against existing backups.
- Items deleted from the server by another device are not removed from the phone's gallery by sync.
- Because the key store requires an unlocked phone, backups only progress while the phone is unlocked (locked runs retry later).

## Documentation

| Document | Covers |
|---|---|
| [`Backend/README.md`](Backend/README.md) | Configuration, Docker and NAS deployment, admin console, management commands |
| [`Backend/codex.md`](Backend/codex.md) | Client–server contract |
| [`Frontend/README.md`](Frontend/README.md) | Android feature list and setup |
| [`Frontend/codex.md`](Frontend/codex.md) | Android architecture in depth: auth, crypto, gallery, backup pipeline |
| [`CLAUDE.md`](CLAUDE.md) | Working notes and invariants for contributors and coding agents |
