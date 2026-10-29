#!/usr/bin/env bash
# Builds and runs the host-side firmware tests. Needs only g++ (no Arduino/PlatformIO toolchain).
# -std=c++11 matches what the ESP32 Arduino 2.x core compiles with.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
CXX="${CXX:-g++}"
FLAGS=(-std=c++11 -Wall -Wextra -Werror -pedantic -O1 -fsanitize=address,undefined -fno-sanitize-recover=undefined)
status=0
for t in test_gesture_detector; do
  "$CXX" "${FLAGS[@]}" "$t.cpp" -o "build/$t"
  "./build/$t" || status=1
done
exit $status
