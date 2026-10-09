#pragma once

#include <vector>

namespace wb {

// Whisper log-mel input for one 30 s window, identical to Hugging Face's
// WhisperFeatureExtractor (128 bins, n_fft 400, hop 160, slaney mel, reflect padding):
// the audio is zero-padded or cut to 480 000 samples and the result is [128][3000],
// row-major (bin, frame). Plain C++ so it can be checked against Python on a host.
constexpr int kMelBins   = 128;
constexpr int kMelFrames = 3000;
constexpr int kChunkSamples = 480000;

std::vector<float> whisper_log_mel(const float * pcm, int n_samples, int threads);

} // namespace wb
