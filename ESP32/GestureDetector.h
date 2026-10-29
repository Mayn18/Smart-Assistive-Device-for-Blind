// GestureDetector.h
//
// Turns a raw button signal into user gestures. Deliberately free of any
// Arduino / ESP-IDF dependency so it can be unit-tested on a normal computer
// (see ESP32/tests/).
//
//   single click  -> Gesture::Read              (fires once the double-click window expires)
//   double click  -> Gesture::ToggleContinuous
//   long press    -> Gesture::Stop              (fires while the button is still held)
//
// All time arithmetic is done with unsigned subtraction, so it stays correct
// when millis() wraps around after ~49.7 days.

#pragma once

#include <stdint.h>

enum class Gesture : uint8_t {
  None = 0,
  Read,
  ToggleContinuous,
  Stop,
};

class GestureDetector {
 public:
  struct Timing {
    uint32_t debounceMs;
    uint32_t doublePressWindowMs;
    uint32_t longPressMs;
    Timing() : debounceMs(30), doublePressWindowMs(350), longPressMs(800) {}
  };

  explicit GestureDetector(const Timing& timing = Timing()) : timing_(timing) {}

  // Call frequently (every millisecond or so) with the current raw button state.
  // Returns at most one gesture per call; Gesture::None most of the time.
  Gesture update(bool pressedRaw, uint32_t nowMs) {
    // --- Debounce: only accept a level once it has been stable long enough. ---
    if (pressedRaw != rawLevel_) {
      rawLevel_ = pressedRaw;
      rawChangedAtMs_ = nowMs;
    }
    if (rawLevel_ != stablePressed_ &&
        static_cast<uint32_t>(nowMs - rawChangedAtMs_) >= timing_.debounceMs) {
      stablePressed_ = rawLevel_;
      if (stablePressed_) {
        onPress(nowMs);
      } else {
        const Gesture g = onRelease(nowMs);
        if (g != Gesture::None) return g;
      }
    }

    // --- Long press fires while still held, so the user gets feedback at once. ---
    if (stablePressed_ && !longPressHandled_ &&
        static_cast<uint32_t>(nowMs - pressedAtMs_) >= timing_.longPressMs) {
      longPressHandled_ = true;
      pendingClick_ = false;
      secondPress_ = false;
      return Gesture::Stop;
    }

    // --- A lone click becomes "Read" once nobody pressed again in time. ---
    // We hold off while the raw level is pressed so a second press that is
    // still being debounced is not lost to the timeout.
    if (pendingClick_ && !rawLevel_ &&
        static_cast<uint32_t>(nowMs - releasedAtMs_) > timing_.doublePressWindowMs) {
      pendingClick_ = false;
      return Gesture::Read;
    }

    return Gesture::None;
  }

 private:
  void onPress(uint32_t nowMs) {
    pressedAtMs_ = nowMs;
    longPressHandled_ = false;
    // A press that starts while a click is pending is the second half of a double press.
    secondPress_ = pendingClick_;
  }

  Gesture onRelease(uint32_t nowMs) {
    if (longPressHandled_) {
      return Gesture::None;  // Stop was already reported while held.
    }
    if (secondPress_) {
      secondPress_ = false;
      pendingClick_ = false;
      return Gesture::ToggleContinuous;
    }
    pendingClick_ = true;
    releasedAtMs_ = nowMs;
    return Gesture::None;
  }

  Timing timing_;

  bool rawLevel_ = false;
  uint32_t rawChangedAtMs_ = 0;
  bool stablePressed_ = false;

  uint32_t pressedAtMs_ = 0;
  uint32_t releasedAtMs_ = 0;
  bool pendingClick_ = false;
  bool secondPress_ = false;
  bool longPressHandled_ = false;
};
