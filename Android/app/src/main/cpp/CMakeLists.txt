cmake_minimum_required(VERSION 3.22.1)
project(mls_frame_crypto CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Native MLS FrameEncryptor/FrameDecryptor bridge. Hidden visibility keeps everything internal except
# the two JNIEXPORT entry points, so nothing clashes with libwebrtc's symbols in the same process.
add_library(mls_frame_crypto SHARED mls_frame_crypto.cpp)
# CRITICAL: stream-webrtc-android's libjingle_peerconnection_so.so is built with Clang's RELATIVE
# C++ ABI vtables (verified by disassembling nativeSetFrameEncryptor: it does `ldrsw` of a 32-bit
# relative offset + add + blr, not an absolute pointer load). Our FrameEncryptor/Decryptor vtables are
# read by WebRTC, so they MUST use the same encoding — otherwise WebRTC reads our absolute 64-bit
# function pointers as 32-bit relative offsets and jumps to garbage (SIGSEGV in nativeSetFrameEncryptor).
target_compile_options(mls_frame_crypto PRIVATE
    -fvisibility=hidden -O2 -Wall
    -fexperimental-relative-c++-abi-vtables)

# Android 15+ / Google Play (from 2025-11-01) require ELF LOAD segments aligned to a 16 KB page so the
# lib loads on devices with 16 KB pages. Force it at link time (belt-and-suspenders over the NDK default).
target_link_options(mls_frame_crypto PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

find_library(log-lib log)
target_link_libraries(mls_frame_crypto ${log-lib})
