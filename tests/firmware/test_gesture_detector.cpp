// Host-side tests for GestureDetector. Build/run with ./run_tests.sh
#include <string.h>

#include <utility>
#include <vector>

#include "../../ESP32/GestureDetector.h"
#include "test_util.h"

struct Press {
  uint32_t atMs;
  bool pressed;
};
struct Fired {
  uint32_t atMs;
  Gesture gesture;
};

// Steps the detector once per millisecond, exactly like the firmware loop(),
// applying button level changes at the given times. `start` may be near
// UINT32_MAX to exercise millis() wrap-around.
static std::vector<Fired> simulate(const std::vector<Press>& events, uint32_t start,
                                   uint32_t durationMs,
                                   GestureDetector detector = GestureDetector()) {
  std::vector<Fired> fired;
  bool level = false;
  size_t next = 0;
  for (uint32_t i = 0; i <= durationMs; ++i) {
    const uint32_t now = start + i;  // wraps naturally
    while (next < events.size() && static_cast<uint32_t>(start + events[next].atMs) == now) {
      level = events[next].pressed;
      ++next;
    }
    const Gesture g = detector.update(level, now);
    if (g != Gesture::None) fired.push_back({i, g});
  }
  return fired;
}

static void test_single_click_reads_after_window() {
  // press 100..180ms, then nothing.
  const auto fired = simulate({{100, true}, {180, false}}, 1000, 1500);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) {
    CHECK(fired[0].gesture == Gesture::Read);
    // Must wait out the 350ms double-click window after release (+ debounce).
    CHECK(fired[0].atMs > 180 + 350);
    CHECK(fired[0].atMs < 180 + 350 + 100);
  }
}

static void test_double_click_toggles_and_does_not_read() {
  const auto fired = simulate({{100, true}, {160, false}, {300, true}, {360, false}}, 5000, 2000);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) CHECK(fired[0].gesture == Gesture::ToggleContinuous);
}

static void test_long_press_stops_while_held_exactly_once() {
  // Held for 2 seconds.
  const auto fired = simulate({{100, true}, {2100, false}}, 0, 4000);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) {
    CHECK(fired[0].gesture == Gesture::Stop);
    // Fires ~800ms after the (debounced) press, long before release at 2100.
    CHECK(fired[0].atMs >= 100 + 800);
    CHECK(fired[0].atMs < 100 + 800 + 60);
  }
}

static void test_contact_bounce_is_ignored() {
  // 10ms-spaced chatter is shorter than the 30ms debounce and must not register.
  std::vector<Press> chatter;
  for (uint32_t t = 100; t < 160; t += 10) chatter.push_back({t, ((t / 10) % 2) == 0});
  chatter.push_back({160, false});
  const auto fired = simulate(chatter, 0, 1500);
  CHECK_EQ(fired.size(), 0);
}

static void test_bounce_on_release_does_not_double_fire() {
  // A real click whose release bounces: 100 down, 180 up, 185 down, 190 up.
  const auto fired = simulate({{100, true}, {180, false}, {185, true}, {190, false}}, 0, 1500);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) CHECK(fired[0].gesture == Gesture::Read);
}

static void test_two_slow_clicks_are_two_reads() {
  const auto fired = simulate({{100, true}, {160, false}, {900, true}, {960, false}}, 0, 3000);
  CHECK_EQ(fired.size(), 2);
  if (fired.size() == 2) {
    CHECK(fired[0].gesture == Gesture::Read);
    CHECK(fired[1].gesture == Gesture::Read);
  }
}

static void test_triple_click_is_toggle_then_read() {
  const auto fired = simulate({{100, true}, {160, false}, {250, true}, {310, false},
                               {400, true}, {460, false}},
                              0, 3000);
  CHECK_EQ(fired.size(), 2);
  if (fired.size() == 2) {
    CHECK(fired[0].gesture == Gesture::ToggleContinuous);
    CHECK(fired[1].gesture == Gesture::Read);
  }
}

static void test_long_second_press_is_only_stop() {
  const auto fired = simulate({{100, true}, {160, false}, {250, true}, {1700, false}}, 0, 4000);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) CHECK(fired[0].gesture == Gesture::Stop);
}

static void test_works_across_millis_wraparound() {
  // Start 200ms before the 32-bit counter wraps.
  const uint32_t nearWrap = 0xFFFFFFFFu - 200u;
  auto fired = simulate({{100, true}, {160, false}}, nearWrap, 1500);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) CHECK(fired[0].gesture == Gesture::Read);

  fired = simulate({{100, true}, {160, false}, {300, true}, {360, false}}, nearWrap, 1500);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) CHECK(fired[0].gesture == Gesture::ToggleContinuous);

  fired = simulate({{100, true}, {2100, false}}, nearWrap, 3500);
  CHECK_EQ(fired.size(), 1);
  if (fired.size() == 1) CHECK(fired[0].gesture == Gesture::Stop);
}

static void test_idle_button_never_fires() {
  CHECK_EQ(simulate({}, 0, 5000).size(), 0);
}

int main() {
  RUN(test_single_click_reads_after_window);
  RUN(test_double_click_toggles_and_does_not_read);
  RUN(test_long_press_stops_while_held_exactly_once);
  RUN(test_contact_bounce_is_ignored);
  RUN(test_bounce_on_release_does_not_double_fire);
  RUN(test_two_slow_clicks_are_two_reads);
  RUN(test_triple_click_is_toggle_then_read);
  RUN(test_long_second_press_is_only_stop);
  RUN(test_works_across_millis_wraparound);
  RUN(test_idle_button_never_fires);
  return finish("GestureDetector");
}
