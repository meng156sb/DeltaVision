from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from db import (
    connect,
    frame_stats,
    get_detections,
    get_frame,
    init_db,
    init_paths,
    insert_frame,
    list_frames,
    replace_detections,
    save_review,
    upsert_session,
)
from exporter import export_yolo


APP_ROOT = Path(__file__).resolve().parent
RUNTIME_ROOT = APP_ROOT / "runtime"
PATHS = init_paths(RUNTIME_ROOT)
TOKEN = os.environ.get("DELTA_VISION_COLLECTOR_TOKEN", "delta-token")

app = FastAPI(title="DeltaVision Collector")
templates = Jinja2Templates(directory=str(APP_ROOT / "templates"))
app.mount("/dataset", StaticFiles(directory=str(PATHS.dataset_root)), name="dataset")
app.mount("/static", StaticFiles(directory=str(APP_ROOT / "static")), name="static")

conn = connect(PATHS.db_path)
init_db(conn)


def verify_token(header_value: str | None) -> None:
    if TOKEN and header_value != TOKEN:
        raise HTTPException(status_code=401, detail="invalid collector token")


def normalize_status(status: str) -> str:
    value = status.strip().lower()
    aliases = {
        "pending_review": "pending_review",
        "auto_accepted": "auto_accepted",
        "approved": "approved",
        "rejected": "rejected",
    }
    return aliases.get(value, value)


def write_yolo_label(path: Path, boxes: list[dict[str, Any]], roi_width: int, roi_height: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for box in boxes:
        width = float(box["right"]) - float(box["left"])
        height = float(box["bottom"]) - float(box["top"])
        center_x = float(box["left"]) + width / 2.0
        center_y = float(box["top"]) + height / 2.0
        lines.append(
            f"0 {center_x / roi_width:.6f} {center_y / roi_height:.6f} {width / roi_width:.6f} {height / roi_height:.6f}"
        )
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


@app.get("/", response_class=HTMLResponse)
def index(request: Request, status: str = "pending_review") -> HTMLResponse:
    rows = list_frames(conn, status=normalize_status(status), limit=30)
    items = []
    for row in rows:
        detections = get_detections(conn, row["id"], "pseudo")
        items.append({"frame": row, "detections_json": json.dumps(detections, ensure_ascii=False, indent=2)})
    return templates.TemplateResponse(
        request,
        "index.html",
        {
            "stats": frame_stats(conn),
            "items": items,
            "status": status,
            "collector_token": TOKEN,
        },
    )


@app.post("/ingest/frame")
async def ingest_frame(
    image: UploadFile = File(...),
    metadata: str = Form(...),
    detections: str = Form(...),
    review_status: str = Form(...),
    frame_hash: str = Form(...),
    x_collector_token: str | None = Header(default=None),
) -> dict[str, Any]:
    verify_token(x_collector_token)
    metadata_obj = json.loads(metadata)
    detections_obj = json.loads(detections)
    review_status = normalize_status(review_status)
    created_at = int(time.time())
    upsert_session(
        conn,
        session_id=metadata_obj["sessionId"],
        device_id=metadata_obj["deviceId"],
        game_package=metadata_obj["gamePackage"],
        created_at=created_at,
    )

    session_image_dir = PATHS.raw_images_dir / metadata_obj["sessionId"]
    session_image_dir.mkdir(parents=True, exist_ok=True)
    session_pseudo_dir = PATHS.pseudo_labels_dir / metadata_obj["sessionId"]
    session_pseudo_dir.mkdir(parents=True, exist_ok=True)

    image_name = f"{metadata_obj['timestampNs']}.jpg"
    label_name = f"{metadata_obj['timestampNs']}.txt"
    image_path = session_image_dir / image_name
    pseudo_label_path = session_pseudo_dir / label_name

    image_path.write_bytes(await image.read())
    roi_rect = metadata_obj["roiPixelRect"]
    write_yolo_label(pseudo_label_path, detections_obj, int(roi_rect["width"]), int(roi_rect["height"]))

    frame_id = insert_frame(
        conn,
        session_id=metadata_obj["sessionId"],
        frame_hash=frame_hash,
        review_status=review_status,
        screen_width=int(metadata_obj["screenWidth"]),
        screen_height=int(metadata_obj["screenHeight"]),
        roi_norm_rect_json=json.dumps(metadata_obj["roiNormRect"], ensure_ascii=False),
        roi_pixel_rect_json=json.dumps(metadata_obj["roiPixelRect"], ensure_ascii=False),
        image_path=str(image_path.relative_to(PATHS.dataset_root)),
        pseudo_label_path=str(pseudo_label_path.relative_to(PATHS.dataset_root)),
        timestamp_ns=int(metadata_obj["timestampNs"]),
        created_at=created_at,
    )
    if frame_id is None:
        return {"status": "duplicate", "frame_hash": frame_hash}

    replace_detections(conn, frame_id, detections_obj, "pseudo")
    return {"status": "ok", "frame_id": frame_id}


@app.post("/review/{frame_id}")
async def review_frame(request: Request, frame_id: int) -> RedirectResponse:
    form = await request.form()
    decision = normalize_status(str(form.get("decision", "approved")))
    boxes = json.loads(str(form.get("boxes_json", "[]")))
    note = str(form.get("note", ""))
    frame = get_frame(conn, frame_id)
    if frame is None:
        raise HTTPException(status_code=404, detail="frame not found")
    reviewed_label_path = PATHS.reviewed_labels_dir / frame["session_id"] / Path(frame["pseudo_label_path"]).name
    roi_pixel_rect = json.loads(frame["roi_pixel_rect_json"])
    write_yolo_label(reviewed_label_path, boxes, int(roi_pixel_rect["width"]), int(roi_pixel_rect["height"]))
    save_review(
        conn,
        frame_id=frame_id,
        decision=decision,
        boxes=boxes,
        reviewed_label_path=str(reviewed_label_path.relative_to(PATHS.dataset_root)),
        note=note,
        created_at=int(time.time()),
    )
    return RedirectResponse(url="/", status_code=303)


@app.post("/export/yolo")
def export_dataset() -> dict[str, Any]:
    export_path = export_yolo(conn, PATHS.exports_dir)
    return {"status": "ok", "export_path": str(export_path)}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=False)

