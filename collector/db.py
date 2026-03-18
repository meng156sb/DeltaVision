from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class CollectorPaths:
    root: Path
    dataset_root: Path
    db_path: Path
    raw_images_dir: Path
    pseudo_labels_dir: Path
    reviewed_labels_dir: Path
    exports_dir: Path


def init_paths(root: Path) -> CollectorPaths:
    dataset_root = root / "dataset"
    paths = CollectorPaths(
        root=root,
        dataset_root=dataset_root,
        db_path=root / "collector.sqlite3",
        raw_images_dir=dataset_root / "images" / "raw",
        pseudo_labels_dir=dataset_root / "labels" / "pseudo",
        reviewed_labels_dir=dataset_root / "labels" / "reviewed",
        exports_dir=dataset_root / "exports",
    )
    for path in [
        paths.raw_images_dir,
        paths.pseudo_labels_dir,
        paths.reviewed_labels_dir,
        paths.exports_dir,
    ]:
        path.mkdir(parents=True, exist_ok=True)
    return paths


def connect(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            game_package TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS frames (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL,
            frame_hash TEXT NOT NULL UNIQUE,
            review_status TEXT NOT NULL,
            screen_width INTEGER NOT NULL,
            screen_height INTEGER NOT NULL,
            roi_norm_rect_json TEXT NOT NULL,
            roi_pixel_rect_json TEXT NOT NULL,
            image_path TEXT NOT NULL,
            pseudo_label_path TEXT NOT NULL,
            reviewed_label_path TEXT,
            timestamp_ns INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(session_id) REFERENCES sessions(id)
        );

        CREATE TABLE IF NOT EXISTS detections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            frame_id INTEGER NOT NULL,
            left_px REAL NOT NULL,
            top_px REAL NOT NULL,
            right_px REAL NOT NULL,
            bottom_px REAL NOT NULL,
            confidence REAL NOT NULL,
            label TEXT NOT NULL,
            track_id INTEGER,
            source TEXT NOT NULL,
            FOREIGN KEY(frame_id) REFERENCES frames(id)
        );

        CREATE TABLE IF NOT EXISTS reviews (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            frame_id INTEGER NOT NULL,
            decision TEXT NOT NULL,
            boxes_json TEXT NOT NULL,
            note TEXT,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(frame_id) REFERENCES frames(id)
        );
        """
    )
    conn.commit()


def upsert_session(conn: sqlite3.Connection, session_id: str, device_id: str, game_package: str, created_at: int) -> None:
    conn.execute(
        """
        INSERT INTO sessions(id, device_id, game_package, created_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET device_id=excluded.device_id, game_package=excluded.game_package
        """,
        (session_id, device_id, game_package, created_at),
    )
    conn.commit()


def insert_frame(
    conn: sqlite3.Connection,
    *,
    session_id: str,
    frame_hash: str,
    review_status: str,
    screen_width: int,
    screen_height: int,
    roi_norm_rect_json: str,
    roi_pixel_rect_json: str,
    image_path: str,
    pseudo_label_path: str,
    timestamp_ns: int,
    created_at: int,
) -> int | None:
    cursor = conn.execute(
        """
        INSERT OR IGNORE INTO frames(
            session_id, frame_hash, review_status, screen_width, screen_height,
            roi_norm_rect_json, roi_pixel_rect_json, image_path, pseudo_label_path,
            timestamp_ns, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            session_id,
            frame_hash,
            review_status,
            screen_width,
            screen_height,
            roi_norm_rect_json,
            roi_pixel_rect_json,
            image_path,
            pseudo_label_path,
            timestamp_ns,
            created_at,
            created_at,
        ),
    )
    conn.commit()
    return cursor.lastrowid or None


def replace_detections(conn: sqlite3.Connection, frame_id: int, detections: list[dict[str, Any]], source: str) -> None:
    conn.execute("DELETE FROM detections WHERE frame_id = ? AND source = ?", (frame_id, source))
    for item in detections:
        conn.execute(
            """
            INSERT INTO detections(frame_id, left_px, top_px, right_px, bottom_px, confidence, label, track_id, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                frame_id,
                item["left"],
                item["top"],
                item["right"],
                item["bottom"],
                item["confidence"],
                item.get("label", "person_body"),
                item.get("trackId"),
                source,
            ),
        )
    conn.commit()


def list_frames(conn: sqlite3.Connection, status: str = "pending_review", limit: int = 50) -> list[sqlite3.Row]:
    return conn.execute(
        """
        SELECT * FROM frames
        WHERE lower(review_status) = ?
        ORDER BY created_at DESC
        LIMIT ?
        """,
        (status.lower(), limit),
    ).fetchall()


def get_frame(conn: sqlite3.Connection, frame_id: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM frames WHERE id = ?", (frame_id,)).fetchone()


def get_detections(conn: sqlite3.Connection, frame_id: int, source: str | None = None) -> list[dict[str, Any]]:
    if source is None:
        rows = conn.execute("SELECT * FROM detections WHERE frame_id = ? ORDER BY id ASC", (frame_id,)).fetchall()
    else:
        rows = conn.execute("SELECT * FROM detections WHERE frame_id = ? AND source = ? ORDER BY id ASC", (frame_id, source)).fetchall()
    return [dict(row) for row in rows]


def save_review(
    conn: sqlite3.Connection,
    *,
    frame_id: int,
    decision: str,
    boxes: list[dict[str, Any]],
    reviewed_label_path: str,
    note: str,
    created_at: int,
) -> None:
    conn.execute(
        "INSERT INTO reviews(frame_id, decision, boxes_json, note, created_at) VALUES (?, ?, ?, ?, ?)",
        (frame_id, decision, json.dumps(boxes, ensure_ascii=False), note, created_at),
    )
    conn.execute(
        "UPDATE frames SET review_status = ?, reviewed_label_path = ?, updated_at = ? WHERE id = ?",
        (decision, reviewed_label_path, created_at, frame_id),
    )
    replace_detections(conn, frame_id, boxes, "reviewed")
    conn.commit()


def frame_stats(conn: sqlite3.Connection) -> dict[str, int]:
    rows = conn.execute("SELECT review_status, COUNT(*) AS count FROM frames GROUP BY review_status").fetchall()
    data = {"all": 0}
    for row in rows:
        key = str(row["review_status"]).lower()
        data[key] = data.get(key, 0) + row["count"]
        data["all"] += row["count"]
    return data
