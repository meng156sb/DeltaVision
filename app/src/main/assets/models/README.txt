DeltaVision Android runtime now loads an ONNX detector model.

Place your exported model here on the phone:
  /sdcard/Android/data/com.deltavision.app/files/models/model.onnx

Expected format:
  - Ultralytics YOLO exported to ONNX
  - input size should match the app setting (default 448)
  - single-class `person_body` is recommended

The app uses a pure Kotlin + ONNX Runtime inference path for now,
so `model.ncnn.param` / `model.ncnn.bin` are no longer used by the current MVP.
