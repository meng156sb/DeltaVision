from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


def main() -> int:
    parser = argparse.ArgumentParser(description="Train DeltaVision person_body model with Ultralytics YOLO")
    parser.add_argument("data", help="path to data.yaml")
    parser.add_argument("--weights", default="yolo11n.pt")
    parser.add_argument("--imgsz", type=int, default=448)
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--project", default="runs/deltavision")
    parser.add_argument("--name", default="person_body")
    args = parser.parse_args()

    model = YOLO(args.weights)
    model.train(
        data=args.data,
        imgsz=args.imgsz,
        epochs=args.epochs,
        patience=20,
        project=args.project,
        name=args.name,
        mosaic=1.0,
        hsv_h=0.015,
        hsv_s=0.7,
        hsv_v=0.4,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
