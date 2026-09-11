#!/bin/bash
set -euo pipefail

$CXX $CXXFLAGS -std=c++17 \
  -I Android/app/src/main/cpp \
  Android/app/src/test/cpp/frame_copy_fuzz.cpp \
  $LIB_FUZZING_ENGINE \
  -o "$OUT/frame_copy_fuzz"
