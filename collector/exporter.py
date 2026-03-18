from __future__ import annotations

import random
import shutil
import sqlite3
from pathlib import Path


def export_yolo(conn: sqlite3.Connection, export_root: Path, dataset_name: str = "yolo_v1") -> Path:
    target_root = export_root / dataset_name
    dataset_root = export_root.parent
    if target_root.exists():
        shutil.rmtree(target_root)
    for split in ["train", "val", "test"]:
        (target_root / "images" / split).mkdir(parents=True, exist_ok=True)
        (target_root / "labels" / split).mkdir(parents=True, exist_ok=True)

    session_rows = conn.execute(
        "SELECT session_id, COUNT(*) AS frame_count FROM frames WHERE lower(review_status) IN ('approved', 'auto_accepted') GROUP BY session_id"
    ).fetchall()
    session_ids = [row[0] for row in session_rows]
    random.Random(42).shuffle(session_ids)
    total = len(session_ids)
    train_cutoff = int(total * 0.8)
    val_cutoff = int(total * 0.9)
    split_map: dict[str, str] = {}
    for index, session_id in enumerate(session_ids):
        split_map[session_id] = "train" if index < train_cutoff else "val" if index < val_cutoff else "test"

    rows = conn.execute(
        "SELECT session_id, image_path, pseudo_label_path, reviewed_label_path, review_status FROM frames WHERE lower(review_status) IN ('approved', 'auto_accepted')"
    ).fetchall()
    for session_id, image_path, pseudo_label_path, reviewed_label_path, review_status in rows:
        split = split_map.get(session_id, "train")
        label_path = reviewed_label_path if str(review_status).lower() == "approved" and reviewed_label_path else pseudo_label_path
        image_name = Path(image_path).name
        label_name = Path(label_path).name
        shutil.copy2(dataset_root / image_path, target_root / "images" / split / image_name)
        shutil.copy2(dataset_root / label_path, target_root / "labels" / split / label_name)

    (target_root / "data.yaml").write_text(
        """
path: .
train: images/train
val: images/val
test: images/test
names:
  0: person_body
""".strip()
        + "\n",
        encoding="utf-8",
    )
    return target_root
