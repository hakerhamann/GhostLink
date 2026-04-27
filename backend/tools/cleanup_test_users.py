#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path


TEST_PREFIXES = ("test_", "demo_", "autotest_", "tmp_", "ghost_test_")


def main() -> int:
    parser = argparse.ArgumentParser(description="Delete clearly test accounts from GhostLink DB")
    parser.add_argument("--db", default=str(Path(__file__).resolve().parents[1] / "reserv.db"))
    parser.add_argument("--apply", action="store_true", help="Apply deletion (without this flag, dry-run only)")
    args = parser.parse_args()

    db_path = Path(args.db).resolve()
    if not db_path.exists():
        print(f"DB not found: {db_path}")
        return 1

    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row

    where = " OR ".join(["LOWER(login) LIKE ?"] * len(TEST_PREFIXES))
    patterns = [f"{prefix}%" for prefix in TEST_PREFIXES]
    rows = connection.execute(
        f"SELECT id, login, display_name, created_at FROM users WHERE {where} ORDER BY id",
        tuple(patterns),
    ).fetchall()

    if not rows:
        print("No test users found.")
        connection.close()
        return 0

    print("Matched users:")
    for row in rows:
        print(f"- id={row['id']} login={row['login']} display_name={row['display_name']}")

    if not args.apply:
        print("Dry-run mode. Use --apply to delete.")
        connection.close()
        return 0

    user_ids = [int(row["id"]) for row in rows]
    placeholders = ",".join(["?"] * len(user_ids))
    connection.execute(
        f"DELETE FROM users WHERE id IN ({placeholders})",
        tuple(user_ids),
    )
    connection.commit()
    connection.close()
    print(f"Deleted {len(user_ids)} test users.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
