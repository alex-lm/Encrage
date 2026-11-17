from __future__ import annotations

import argparse
import getpass
import uuid

from app.config import get_settings
from app.database import CURRENT_SCHEMA_VERSION, Database
from app.main import cleanup_abandoned_uploads, process_delete_queue
from app.security import hash_password, utc_iso
from dotenv import load_dotenv

load_dotenv()

def get_database() -> tuple[Database, object]:
    settings = get_settings()
    database = Database(settings.database_path, settings.media_root)
    database.initialize()
    return database, settings


def create_user(username: str, quota_bytes: int) -> None:
    database, _ = get_database()
    password = getpass.getpass("Password: ")
    confirmation = getpass.getpass("Confirm password: ")
    if password != confirmation:
        raise SystemExit("Passwords do not match")
    if len(password) < 12:
        raise SystemExit("Password must contain at least 12 characters")
    user_id = str(uuid.uuid4())
    now = utc_iso()
    with database.transaction(immediate=True) as connection:
        connection.execute(
            """
            INSERT INTO users (
                user_id, username, password_hash, space_allocated_bytes,
                space_used_bytes, folder_location, created_at, updated_at, is_active
            ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, 1)
            """,
            (user_id, username.strip(), hash_password(password), quota_bytes, user_id, now, now),
        )
    print(f"Created user {username!r} with ID {user_id}")


def run_migrations() -> None:
    database, _ = get_database()
    with database.connection() as connection:
        issues = connection.execute("SELECT COUNT(*) FROM legacy_migration_issues").fetchone()[0]
    print(f"Database is at schema version {CURRENT_SCHEMA_VERSION}; legacy issues: {issues}")


def cleanup_uploads() -> None:
    database, settings = get_database()
    cleaned = cleanup_abandoned_uploads(database, settings)
    deleted = process_delete_queue(database, settings)
    print(f"Cleaned {cleaned} abandoned uploads and processed {deleted} deferred deletions")


def main() -> None:
    parser = argparse.ArgumentParser(description="Encrage administration")
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create-user", help="Create a local user")
    create.add_argument("username")
    create.add_argument("--quota-bytes", type=int, required=True)
    subparsers.add_parser("migrate", help="Apply schema and safe legacy-row migrations")
    subparsers.add_parser("cleanup-uploads", help="Remove expired incomplete uploads and retry deletions")
    args = parser.parse_args()
    if args.command == "create-user":
        if args.quota_bytes <= 0:
            raise SystemExit("--quota-bytes must be positive")
        create_user(args.username, args.quota_bytes)
    elif args.command == "migrate":
        run_migrations()
    elif args.command == "cleanup-uploads":
        cleanup_uploads()


if __name__ == "__main__":
    main()
