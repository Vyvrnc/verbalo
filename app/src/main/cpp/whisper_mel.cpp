#include "whisper_mel.h"

#include <algorithm>
#include <cmath>
#include <thread>

namespace wb {

namespace {

constexpr int kSampleRate = 16000;
constexpr int kNfft = 400;
constexpr int kHop  = 160;
constexpr int kFreqBins = kNfft / 2 + 1;   // 201

double hz_to_mel(double f) {               // slaney scale, as librosa / transformers
    const double f_sp = 200.0 / 3, min_log_hz = 1000.0, min_log_mel = min_log_hz / f_sp;
    const double logstep = std::log(6.4) / 27.0;
    return f < min_log_hz ? f / f_sp : min_log_mel + std::log(f / min_log_hz) / logstep;
}

double mel_to_hz(double m) {
    const double f_sp = 200.0 / 3, min_log_hz = 1000.0, min_log_mel = min_log_hz / f_sp;
    const double logstep = std::log(6.4) / 27.0;
    return m < min_log_mel ? m * f_sp : min_log_hz * std::exp(logstep * (m - min_log_mel));
}

struct Tables {
    std::vector<float> filters;   // [kMelBins][kFreqBins], slaney-normalised triangles
    std::vector<float> window;    // periodic Hann
    std::vector<float> cos_t, sin_t;   // [kFreqBins][kNfft]

    Tables() : filters(kMelBins * kFreqBins), window(kNfft),
               cos_t(kFreqBins * kNfft), sin_t(kFreqBins * kNfft) {
        std::vector<double> edges(kMelBins + 2);
        const double m0 = hz_to_mel(0.0), m1 = hz_to_mel(kSampleRate / 2.0);
        for (int i = 0; i < kMelBins + 2; ++i) edges[i] = mel_to_hz(m0 + (m1 - m0) * i / (kMelBins + 1));
        for (int m = 0; m < kMelBins; ++m) {
            const double enorm = 2.0 / (edges[m + 2] - edges[m]);
            for (int b = 0; b < kFreqBins; ++b) {
                const double f = (kSampleRate / 2.0) * b / (kFreqBins - 1);
                const double down = (f - edges[m]) / (edges[m + 1] - edges[m]);
                const double up   = (edges[m + 2] - f) / (edges[m + 2] - edges[m + 1]);
                filters[m * kFreqBins + b] = (float) (std::max(0.0, std::min(down, up)) * enorm);
            }
        }
        for (int n = 0; n < kNfft; ++n) window[n] = (float) (0.5 - 0.5 * std::cos(2.0 * M_PI * n / kNfft));
        for (int k = 0; k < kFreqBins; ++k) {
            for (int n = 0; n < kNfft; ++n) {
                const double a = 2.0 * M_PI * (double) ((long) k * n % kNfft) / kNfft;
                cos_t[k * kNfft + n] = (float) std::cos(a);
                sin_t[k * kNfft + n] = (float) std::sin(a);
            }
        }
    }
};

const Tables & tables() {
    static const Tables t;
    return t;
}

} // namespace

std::vector<float> whisper_log_mel(const float * pcm, int n_samples, int threads) {
    const Tables & t = tables();
    const int pad = kNfft / 2;
    // Zero-pad/cut to 30 s, then reflect-pad by n_fft/2 on both sides (center=True).
    std::vector<float> x(kChunkSamples + 2 * pad, 0.0f);
    const int n = std::min(std::max(n_samples, 0), kChunkSamples);
    std::copy(pcm, pcm + n, x.begin() + pad);
    for (int i = 0; i < pad; ++i) {
        x[pad - 1 - i] = x[pad + 1 + i];
        x[pad + kChunkSamples + i] = x[pad + kChunkSamples - 2 - i];
    }

    std::vector<float> out((size_t) kMelBins * kMelFrames);
    auto work = [&](int f0, int f1) {
        std::vector<float> frame(kNfft), power(kFreqBins);
        for (int f = f0; f < f1; ++f) {
            const float * src = x.data() + (size_t) f * kHop;
            for (int i = 0; i < kNfft; ++i) frame[i] = src[i] * t.window[i];
            for (int k = 0; k < kFreqBins; ++k) {
                const float * c = &t.cos_t[k * kNfft];
                const float * s = &t.sin_t[k * kNfft];
                float re = 0, im = 0;
                for (int i = 0; i < kNfft; ++i) { re += frame[i] * c[i]; im += frame[i] * s[i]; }
                power[k] = re * re + im * im;
            }
            for (int m = 0; m < kMelBins; ++m) {
                const float * w = &t.filters[m * kFreqBins];
                float acc = 0;
                for (int k = 0; k < kFreqBins; ++k) acc += w[k] * power[k];
                out[(size_t) m * kMelFrames + f] = std::log10(std::max(acc, 1e-10f));
            }
        }
    };
    const int nt = std::max(1, std::min(threads, 16));
    std::vector<std::thread> pool;
    for (int i = 0; i < nt; ++i) {
        const int f0 = kMelFrames * i / nt, f1 = kMelFrames * (i + 1) / nt;
        pool.emplace_back(work, f0, f1);
    }
    for (auto & th : pool) th.join();

    const float mx = *std::max_element(out.begin(), out.end());
    for (float & v : out) v = (std::max(v, mx - 8.0f) + 4.0f) / 4.0f;
    return out;
}

} // namespace wb
