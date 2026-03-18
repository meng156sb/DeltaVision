from __future__ import annotations

import random
import shutil
import sqlite3
from pathlib import Path


APPROVED_STATUSES = ("approved", "auto_accepted")
RANDOM_SEED = 42


def export_yolo(conn: sqlite3.Connection, export_root: Path, dataset_name: str = "yolo_v1") -> Path:
    target_root = export_root / dataset_name
    dataset_root = export_root.parent
    if target_root.exists():
        shutil.rmtree(target_root)
    for split in ["train", "val", "test"]:
        (target_root / "images" / split).mkdir(parents=True, exist_ok=True)
        (target_root / "labels" / split).mkdir(parents=True, exist_ok=True)

    rows = list(
        conn.execute(
            "SELECT id, session_id, image_path, pseudo_label_path, reviewed_label_path, review_status "
            "FROM frames WHERE lower(review_status) IN ('approved', 'auto_accepted')"
        ).fetchall()
    )
    split_assignments = build_split_assignments(rows)

    exported_items: list[tuple[Path, Path, str]] = []
    for row in rows:
        split = split_assignments.get(int(row["id"]), "train")
        label_path = row["reviewed_label_path"] if str(row["review_status"]).lower() == "approved" and row["reviewed_label_path"] else row["pseudo_label_path"]
        image_name = Path(row["image_path"]).name
        label_name = Path(label_path).name
        source_image = dataset_root / row["image_path"]
        source_label = dataset_root / label_path
        target_image = target_root / "images" / split / image_name
        target_label = target_root / "labels" / split / label_name
        shutil.copy2(source_image, target_image)
        shutil.copy2(source_label, target_label)
        exported_items.append((target_image, target_label, split))

    ensure_minimum_training_splits(target_root, exported_items)

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


def build_split_assignments(rows: list[sqlite3.Row]) -> dict[int, str]:
    if not rows:
        return {}
    session_ids = sorted({str(row["session_id"]) for row in rows})
    if len(session_ids) >= 3:
        return build_session_split_assignments(rows, session_ids)
    return build_frame_split_assignments(rows)


def build_session_split_assignments(rows: list[sqlite3.Row], session_ids: list[str]) -> dict[int, str]:
    shuffled_sessions = session_ids[:]
    random.Random(RANDOM_SEED).shuffle(shuffled_sessions)
    train_count, val_count, test_count = compute_session_split_counts(len(shuffled_sessions))
    split_map: dict[str, str] = {}
    for index, session_id in enumerate(shuffled_sessions):
        if index < train_count:
            split_map[session_id] = "train"
        elif index < train_count + val_count:
            split_map[session_id] = "val"
        else:
            split_map[session_id] = "test"
    return {int(row["id"]): split_map.get(str(row["session_id"]), "train") for row in rows}


def build_frame_split_assignments(rows: list[sqlite3.Row]) -> dict[int, str]:
    shuffled_rows = rows[:]
    random.Random(RANDOM_SEED).shuffle(shuffled_rows)
    total = len(shuffled_rows)
    if total == 1:
        train_count, val_count, test_count = 1, 0, 0
    elif total == 2:
        train_count, val_count, test_count = 1, 1, 0
    elif total < 5:
        train_count, val_count, test_count = total - 1, 1, 0
    else:
        train_count, val_count, test_count = total - 1, 1, 0

    assignments: dict[int, str] = {}
    for index, row in enumerate(shuffled_rows):
        row_id = int(row["id"])
        if index < train_count:
            assignments[row_id] = "train"
        elif index < train_count + val_count:
            assignments[row_id] = "val"
        else:
            assignments[row_id] = "test"
    return assignments


def compute_session_split_counts(total: int) -> tuple[int, int, int]:
    if total <= 0:
        return 0, 0, 0
    if total == 1:
        return 1, 0, 0
    if total == 2:
        return 1, 1, 0

    train_count = max(1, int(total * 0.8))
    val_count = max(1, int(total * 0.1))
    test_count = max(1, total - train_count - val_count)

    while train_count + val_count + test_count > total:
        if train_count > val_count and train_count > 1:
            train_count -= 1
        elif val_count > 1:
            val_count -= 1
        else:
            test_count -= 1

    if test_count == 0 and total >= 3:
        if train_count > val_count and train_count > 1:
            train_count -= 1
        elif val_count > 1:
            val_count -= 1
        else:
            train_count = max(1, train_count - 1)
        test_count = 1

    while train_count + val_count + test_count < total:
        train_count += 1

    return train_count, val_count, test_count


def ensure_minimum_training_splits(target_root: Path, exported_items: list[tuple[Path, Path, str]]) -> None:
    val_images = list((target_root / "images" / "val").glob("*"))
    train_images = list((target_root / "images" / "train").glob("*"))
    if val_images or not train_images:
        return

    train_item = next(((image, label) for image, label, split in exported_items if split == "train"), None)
    if train_item is None:
        return

    source_image, source_label = train_item
    shutil.copy2(source_image, target_root / "images" / "val" / source_image.name)
    shutil.copy2(source_label, target_root / "labels" / "val" / source_label.name)
