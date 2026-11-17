# Encrage

Private, client-side encrypted photo and video cloud.

Android encrypts media on the device before upload. The backend stores opaque ciphertext only — it never receives the media master key and never decodes photos or videos.

## Repository layout

| Path | Role |
|---|---|
| [`Backend/`](Backend/) | FastAPI + SQLite authenticated storage and indexing API |
| [`Frontend/`](Frontend/) | Native Android client (Kotlin, Jetpack Compose) |

## How it works

1. The Android app inventories local MediaStore photos and videos.
2. A Recovery Key (32-byte media master key) is generated or imported and wrapped with Android Keystore.
3. Media is chunked (4 MiB), encrypted with XChaCha20-Poly1305, and uploaded with encrypted thumbnails.
4. The API authenticates users, enforces quotas, and stores encrypted blobs byte-for-byte.
5. The gallery merges local and cloud state offline-first via Room.

## Quick start

### Backend

```powershell
cd Backend
py -3.12 -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
Copy-Item .env.example .env
# Set ENCRAGE_JWT_SECRET in .env to at least 32 random bytes
python manage.py migrate
python manage.py create-user alice --quota-bytes 10737418240
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

API docs: `http://127.0.0.1:8000/docs`

### Frontend

Install Android Studio (SDK Platform 36, JDK 17), then:

```powershell
cd Frontend
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

Point the debug build at your API with `ENCRAGE_DEBUG_API_URL` in `~/.gradle/gradle.properties`, or set the endpoint in the app under Settings / sign-in.

## Security model

- Passwords are submitted once and never persisted on the device.
- Refresh tokens and the media master key use Android Keystore-backed storage.
- Chunk encryption AAD binds protocol version, media ID, blob type, and chunk index.
- The server never sees plaintext media or the master key.
- Production API traffic requires HTTPS (terminate TLS at a reverse proxy).

## Docs

- Backend details: [`Backend/README.md`](Backend/README.md)
- Android client details: [`Frontend/README.md`](Frontend/README.md)
- Client contract notes: [`Backend/codex.md`](Backend/codex.md), [`Frontend/codex.md`](Frontend/codex.md)
