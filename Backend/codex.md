# Android integration notes

This file is the compact client contract. See `README.md` and `/docs` for operational and schema detail.

## Authentication

Send login credentials once as a JSON body to `POST /auth/login`. Store the opaque refresh token in Android Keystore-backed secure storage and keep the access token short-lived. Do not persist the user's password.

Refresh with `POST /auth/refresh` and immediately replace the locally stored refresh token with the returned value. Refresh-token rotation means the previous token cannot be retried after a successful response. If a response is lost after rotation, treat the session as requiring login rather than repeatedly replaying the old token.

All storage calls use `Authorization: Bearer <access_token>`.

## Preparing an upload

Before `POST /media/uploads`, Android should:

1. Read the MediaStore item and extract permitted gallery metadata.
2. Generate and encode the thumbnail.
3. Calculate the keyed privacy-preserving `media_fingerprint`.
4. Send upload metadata to receive `media_id` and `upload_id`.
5. Derive per-media/per-blob keys as designed by the client cryptographic protocol.
6. Encrypt each 4 MiB plaintext chunk independently with XChaCha20-Poly1305.
7. Bind `encryption_version`, returned `media_id`, blob type, and chunk index as associated data.
8. Encrypt the encoded thumbnail independently.

Do not send the media master key, plaintext SHA-256, original filename, GPS, location name, EXIF, or other private data. Put private fields into one client-defined encrypted payload and send its encoded ciphertext as `private_metadata_ciphertext`.

## Binary requests

Chunk and thumbnail PUT requests use the encrypted bytes as the raw request body:

```http
PUT /media/uploads/<upload_id>/chunks/0
Authorization: Bearer <access_token>
Content-Type: application/octet-stream
Content-Length: ...

<opaque encrypted bytes>
```

No multipart encoding is used. The backend SHA-256 returned for an uploaded object covers ciphertext only and is an upload/idempotence aid, not the private media fingerprint.

The same chunk index and bytes are idempotent. Different bytes at the same index replace the incomplete chunk. After completion, the upload session is gone and chunks are immutable; changing media requires a new media fingerprint/upload.

## Resume and download

Use `GET /media/uploads/{upload_id}` after network loss or worker/process death. Upload only missing indexes, ensure the thumbnail is present, then call `complete`.

For download, obtain metadata from `GET /media/{media_id}`. Fetch each encrypted object from:

- `GET /media/{media_id}/chunks/{chunk_index}`
- `GET /media/{media_id}/thumbnail`

Responses are `application/octet-stream` and byte-identical to successful PUT bodies. Decrypt/authenticate each chunk separately, using the same associated data. Chunk endpoints provide random access and resumable download; HTTP Range over a monolithic object is intentionally not part of version 1.

## Deduplication

`POST /media/uploads` returns a completed match with `already_exists: true`. It returns an active matching session with `upload_in_progress: true`. For batch reconciliation after reinstall or Room loss, call `POST /media/fingerprints/check` with up to 1,000 fingerprints.

## Compatibility

Android should accept `encryption_version = 1` for normal media. Rows returned with version `0` are preserved legacy server objects, not the version-1 XChaCha20-Poly1305 chunk protocol, and must not be passed to the version-1 decryptor.
