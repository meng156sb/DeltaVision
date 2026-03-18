#include <jni.h>
#include <android/log.h>

#include <fstream>
#include <string>

namespace {
constexpr char kTag[] = "DeltaVisionNative";
bool g_model_ready = false;

bool file_exists(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    return stream.good();
}
}  // namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_deltavision_app_detector_NativeDetectorEngine_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path,
    jint /* input_size */,
    jfloat /* conf */,
    jfloat /* nms */,
    jint /* max_detections */) {
    const char* raw = env->GetStringUTFChars(model_path, nullptr);
    const std::string root = raw == nullptr ? "" : raw;
    env->ReleaseStringUTFChars(model_path, raw);
    g_model_ready = file_exists(root + "/model.ncnn.param") && file_exists(root + "/model.ncnn.bin");
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeInit model_root=%s ready=%d", root.c_str(), g_model_ready ? 1 : 0);
    return g_model_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_deltavision_app_detector_NativeDetectorEngine_nativeDetect(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray /* rgb_frame */,
    jint /* width */,
    jint /* height */) {
    return env->NewFloatArray(0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_deltavision_app_detector_NativeDetectorEngine_nativeRelease(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    g_model_ready = false;
}
