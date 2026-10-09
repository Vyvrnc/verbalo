#pragma once

#include <atomic>

namespace wb {

// Cancellation/progress of one transcription (the engine API keeps WhisperBench's shape).
struct RunToken {
    std::atomic<bool> abort{false};
    std::atomic<int>  progress{0};
};

} // namespace wb
