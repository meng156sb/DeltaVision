cmake_minimum_required(VERSION 3.22.1)

project(deltavision_detector)

add_library(deltavision_detector SHARED detector_jni.cpp)

find_library(log-lib log)

target_link_libraries(deltavision_detector ${log-lib})
