from __future__ import annotations

import argparse
import getpass

from dotenv import load_dotenv

from app import activity, admin
from app.admin import AdminError
from app.config import get_settings
from app.database import CURRENT_SCHEMA_VERSION, Database
from app.main import cleanup_abandoned_uploads, process_delete_queue

load_dotenv()

CLI_ACTOR = "cli"


def get_database() -> tuple[Database, object]:
    settings = get_settings()
    database = Database(settings.database_path, settings.media_root)
    database.initialize()
    return database, settings


def prompt_new_password(label: str = "Password") -> str:
    password = getpass.getpass(f"{label}: ")
    confirmation = getpass.getpass(f"Confirm {label.lower()}: ")
    if password != confirmation:
        raise SystemExit("Passwords do not match")
    try:
        admin.validate_password(password)
    except AdminError as exc:
        raise SystemExit(exc.message) from exc
    return password


def create_user(username: str, quota_bytes: int) -> None:
    database, _ = get_database()
    password = prompt_new_password()
    try:
        user_id = admin.create_user(database, username, password, quota_bytes, actor=CLI_ACTOR)
    except AdminError as exc:
        raise SystemExit(exc.message) from exc
    print(f"Created user {username.strip()!r} with ID {user_id}")


def reset_password(username: str) -> None:
    database, _ = get_database()
    user_id = admin.find_user_id(database, username)
    if user_id is None:
        raise SystemExit(f"No user named {username!r}")
    password = prompt_new_password("New password")
    revoked = admin.set_user_password(database, user_id, password, actor=CLI_ACTOR)
    print(f"Password reset for {username!r}; {revoked} session(s) were signed out")


def set_console_admin(username: str) -> None:
    database, _ = get_database()
    password = prompt_new_password("Console password")
    try:
        created = admin.set_console_admin(database, username, password)
    except AdminError as exc:
        raise SystemExit(exc.message) from exc
    if created:
        print(f"Created console admin {username.strip()!r}")
    else:
        print(f"Reset the password for console admin {username.strip()!r} and signed it out everywhere")


def remove_console_admin(username: str) -> None:
    database, _ = get_database()
    if not admin.remove_console_admin(database, username):
        raise SystemExit(f"No console admin named {username!r}")
    print(f"Removed console admin {username!r}")


def run_migrations() -> None:
    database, _ = get_database()
    with database.connection() as connection:
        issues = connection.execute("SELECT COUNT(*) FROM legacy_migration_issues").fetchone()[0]
    print(f"Database is at schema version {CURRENT_SCHEMA_VERSION}; legacy issues: {issues}")


def cleanup_uploads() -> None:
    database, settings = get_database()
    cleaned = cleanup_abandoned_uploads(database, settings)
    deleted = process_delete_queue(database, settings)
    pruned = activity.prune_events(database, settings.activity_retention_days)
    print(f"Cleaned {cleaned} abandoned uploads, processed {deleted} deferred deletions, pruned {pruned} old events")


def main() -> None:
    parser = argparse.ArgumentParser(description="Encrage administration")
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create-user", help="Create a local user")
    create.add_argument("username")
    create.add_argument("--quota-bytes", type=int, required=True)
    reset = subparsers.add_parser("reset-password", help="Set a new password and sign out all sessions")
    reset.add_argument("username")
    console_admin = subparsers.add_parser(
        "set-console-admin", help="Create a web console admin, or reset its password"
    )
    console_admin.add_argument("username")
    remove_admin = subparsers.add_parser("remove-console-admin", help="Delete a web console admin")
    remove_admin.add_argument("username")
    subparsers.add_parser("migrate", help="Apply schema and safe legacy-row migrations")
    subparsers.add_parser("cleanup-uploads", help="Remove expired incomplete uploads and retry deletions")
    args = parser.parse_args()
    if args.command == "create-user":
        if args.quota_bytes <= 0:
            raise SystemExit("--quota-bytes must be positive")
        create_user(args.username, args.quota_bytes)
    elif args.command == "reset-password":
        reset_password(args.username)
    elif args.command == "set-console-admin":
        set_console_admin(args.username)
    elif args.command == "remove-console-admin":
        remove_console_admin(args.username)
    elif args.command == "migrate":
        run_migrations()
    elif args.command == "cleanup-uploads":
        cleanup_uploads()


if __name__ == "__main__":
    main()
