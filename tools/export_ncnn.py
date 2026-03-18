from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path

from ultralytics import YOLO


def main() -> int:
    parser = argparse.ArgumentParser(description="Export YOLO checkpoint to ONNX and optionally convert to NCNN via pnnx")
    parser.add_argument("weights", help="path to best.pt")
    parser.add_argument("--imgsz", type=int, default=448)
    parser.add_argument("--output", default="exports/ncnn")
    parser.add_argument("--pnnx", default="pnnx", help="path to pnnx executable")
    args = parser.parse_args()

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    model = YOLO(args.weights)
    onnx_path = Path(model.export(format="onnx", imgsz=args.imgsz))
    target_onnx = output_dir / onnx_path.name
    shutil.copy2(onnx_path, target_onnx)

    if shutil.which(args.pnnx):
        subprocess.run(
            [args.pnnx, str(target_onnx), f"inputshape=[1,3,{args.imgsz},{args.imgsz}]"],
            check=True,
            cwd=output_dir,
        )
    else:
        print("warning: pnnx not found; exported ONNX only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
