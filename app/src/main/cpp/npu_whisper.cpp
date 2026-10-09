// Whisper large-v3-turbo on the Hexagon NPU through QNN HTP.
// Taken from WhisperBench (github.com/Vyvrnc/WhisperBench, app/src/main/cpp/npu_whisper.cpp,
// v0.2.3) and turned into a persistent engine: open once, transcribe many times.
//
// Model: Qualcomm AI Hub "whisper_large_v3_turbo" qnn_context_binary for
// snapdragon-8-elite-for-galaxy (encoder.bin + decoder.bin, float16 I/O, QAIRT 2.50).
// The I/O contract follows qai_hub_models/models/templates/hf_whisper (app.py):
//   encoder: input_features [1,128,3000] -> k/v_cache_cross_{0..3}
//   decoder: input_ids [1,1], attention_mask [1,1,1,200], k/v_cache_self_{i}_in [..199..],
//            k/v_cache_cross_{i}, position_ids [1] -> logits [1,51866,1,1], k/v_cache_self_{i}_out
// One token per decoder call; the self-attention cache is right-aligned, so step n unmasks
// position 199-n. At most 199 steps per 30 s window (the graph's fixed cache length).

#include "npu_whisper.h"

#include "whisper_mel.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <vector>

#ifdef WB_HAVE_QNN
#include "QnnInterface.h"
#include "HTP/QnnHtpDevice.h"
#include "System/QnnSystemInterface.h"
#endif

namespace wb {

#ifdef WB_HAVE_QNN

namespace {

// Token ids of the large-v3 vocabulary (openai/whisper-large-v3-turbo generation_config.json).
constexpr int kEot = 50257, kSot = 50258, kLangFirst = 50259, kLangEnd = 50359;
constexpr int kTranscribe = 50360, kNoTimestamps = 50364;
constexpr int kDecodeLen = 200;          // attention_mask length; cache holds 199
// generation_config "suppress_tokens" (all < kEot or special); specials are excluded anyway.
constexpr int kSuppress[] = {1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60, 61, 62, 63, 90,
    91, 92, 93, 359, 503, 522, 542, 873, 893, 902, 918, 922, 931, 1350, 1853, 1982, 2460, 2627, 3246,
    3253, 3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647, 7273, 9061, 9383, 10428, 10929, 11938, 12033,
    12331, 12562, 13793, 14157, 14635, 15265, 15618, 16553, 16604, 18362, 18956, 20075, 21675, 22520,
    26130, 26161, 26435, 28279, 29464, 31650, 32302, 32470, 36865, 42863, 47425, 49870, 50254};
constexpr float kMaskNeg = -100.0f;

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }

std::string hex(uint64_t v) { char b[24]; snprintf(b, sizeof b, "0x%llx", (unsigned long long) v); return b; }

std::mutex g_log_mutex;
std::string g_log;

void log_cb(const char * fmt, QnnLog_Level_t level, uint64_t, va_list args) {
    char buf[1024];
    va_list copy;
    va_copy(copy, args);
    vsnprintf(buf, sizeof buf, fmt, copy);
    va_end(copy);
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log.size() > 32 * 1024) return;
    static const char * names[] = {"?", "E", "W", "I", "V", "D"};
    g_log += "  [QNN ";
    g_log += (level >= 1 && level <= 5) ? names[level] : "?";
    g_log += "] ";
    g_log += buf;
    if (g_log.back() != '\n') g_log += '\n';
}

struct Fail : std::runtime_error { using std::runtime_error::runtime_error; };

void check(Qnn_ErrorHandle_t e, const char * what) {
    if (e != QNN_SUCCESS) throw Fail(std::string(what) + " selhalo: " + hex(e) + " (kód " + std::to_string(QNN_GET_ERROR_CODE(e)) + ")");
}

size_t dtype_size(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_16: case QNN_DATATYPE_INT_16: case QNN_DATATYPE_UINT_16: return 2;
        case QNN_DATATYPE_FLOAT_32: case QNN_DATATYPE_INT_32: case QNN_DATATYPE_UINT_32: return 4;
        case QNN_DATATYPE_INT_64: case QNN_DATATYPE_UINT_64: return 8;
        default: return 1;
    }
}

// A graph tensor copied out of the binary info (which dies with its system context), with
// its own name/dims storage and the client buffer it currently points at.
struct Slot {
    Qnn_Tensor_t t{};
    std::string name;
    std::vector<uint32_t> dims;
    Qnn_DataType_t dtype = QNN_DATATYPE_UNDEFINED;
    size_t bytes = 0;

    void set_buffer(void * data) {
        Qnn_ClientBuffer_t cb{data, (uint32_t) bytes};
        if (t.version == QNN_TENSOR_VERSION_2) { t.v2.memType = QNN_TENSORMEMTYPE_RAW; t.v2.clientBuf = cb; }
        else { t.v1.memType = QNN_TENSORMEMTYPE_RAW; t.v1.clientBuf = cb; }
    }

    void set_handle(Qnn_MemHandle_t h) {
        if (t.version == QNN_TENSOR_VERSION_2) { t.v2.memType = QNN_TENSORMEMTYPE_MEMHANDLE; t.v2.memHandle = h; }
        else { t.v1.memType = QNN_TENSORMEMTYPE_MEMHANDLE; t.v1.memHandle = h; }
    }
};

// rpcmem from libcdsprpc: ION/DMA-BUF memory the DSP maps directly. Registered with QNN
// (QnnMem_register) it turns graph inputs/outputs into zero-copy buffers; with plain client
// buffers QNN copies every tensor on every execute (31 MB of cross cache per decoder token).
struct RpcMem {
    using AllocFn = void * (*)(int heap_id, uint32_t flags, int size);
    using FreeFn  = void (*)(void * po);
    using ToFdFn  = int (*)(void * po);
    AllocFn alloc = nullptr;
    FreeFn  free  = nullptr;
    ToFdFn  to_fd = nullptr;
    static constexpr int kHeapIdSystem = 25;        // RPCMEM_HEAP_ID_SYSTEM
    static constexpr uint32_t kDefaultFlags = 1;    // RPCMEM_DEFAULT_FLAGS (= RPCMEM_FLAG_CACHED)
    static constexpr uint32_t kUncached = 0;        // RPCMEM_FLAG_UNCACHED

    bool load() {
        void * h = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);   // stays loaded
        if (!h) return false;
        alloc = reinterpret_cast<AllocFn>(dlsym(h, "rpcmem_alloc"));
        free  = reinterpret_cast<FreeFn>(dlsym(h, "rpcmem_free"));
        to_fd = reinterpret_cast<ToFdFn>(dlsym(h, "rpcmem_to_fd"));
        return alloc && free && to_fd;
    }
};

// Tensor memory: rpcmem (fd >= 0) or plain heap.
struct Buffer {
    uint8_t * data = nullptr;
    size_t bytes = 0;
    int fd = -1;
};

std::unique_ptr<Slot> copy_tensor(const Qnn_Tensor_t & src) {
    if (src.version != QNN_TENSOR_VERSION_1 && src.version != QNN_TENSOR_VERSION_2)
        throw Fail("neznámá verze tenzoru " + std::to_string((int) src.version));
    auto s = std::make_unique<Slot>();
    s->t = src;
    auto fill = [&](auto & v) {
        s->name = v.name ? v.name : "";
        s->dims.assign(v.dimensions, v.dimensions + v.rank);
        s->dtype = v.dataType;
        v.name = s->name.c_str();
        v.dimensions = s->dims.data();
    };
    if (s->t.version == QNN_TENSOR_VERSION_2) {
        fill(s->t.v2);
        s->t.v2.isDynamicDimensions = nullptr;
    } else {
        fill(s->t.v1);
    }
    size_t n = 1;
    for (uint32_t d : s->dims) n *= d;
    s->bytes = n * dtype_size(s->dtype);
    return s;
}

struct Graph {
    std::string name;
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    std::vector<std::unique_ptr<Slot>> inputs, outputs;

    Slot * in(const std::string & n) const { for (auto & s : inputs) if (s->name == n) return s.get(); return nullptr; }
    Slot * out(const std::string & n) const { for (auto & s : outputs) if (s->name == n) return s.get(); return nullptr; }
};

std::string describe(const Graph & g) {
    std::ostringstream o;
    auto dump = [&](const char * dir, const std::vector<std::unique_ptr<Slot>> & v) {
        for (auto & s : v) {
            o << "    " << dir << " " << s->name << " [";
            for (size_t i = 0; i < s->dims.size(); ++i) o << (i ? "," : "") << s->dims[i];
            o << "] dtype " << hex(s->dtype) << "\n";
        }
    };
    o << "  graf " << g.name << ": " << g.inputs.size() << " vstupů, " << g.outputs.size() << " výstupů\n";
    dump("in ", g.inputs);
    dump("out", g.outputs);
    return o.str();
}

// float <-> tensor element conversion for the float16/float32 tensors of the model.
void put_floats(const Slot & s, Buffer & buf, const float * src, size_t n) {
    if (s.dtype == QNN_DATATYPE_FLOAT_16) {
        auto * d = reinterpret_cast<_Float16 *>(buf.data);
        for (size_t i = 0; i < n; ++i) d[i] = (_Float16) src[i];
    } else if (s.dtype == QNN_DATATYPE_FLOAT_32) {
        memcpy(buf.data, src, n * 4);
    } else {
        throw Fail("tenzor " + s.name + " má nečekaný typ " + hex(s.dtype));
    }
}

float get_float(const Slot & s, const Buffer & buf, size_t i) {
    return s.dtype == QNN_DATATYPE_FLOAT_16 ? (float) reinterpret_cast<const _Float16 *>(buf.data)[i]
                                            : reinterpret_cast<const float *>(buf.data)[i];
}

class Mapped {
public:
    explicit Mapped(const std::string & path) {
        fd_ = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd_ < 0) throw Fail("nelze otevřít " + path + ": " + strerror(errno));
        struct stat st{};
        fstat(fd_, &st);
        size_ = (size_t) st.st_size;
        data_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
        if (data_ == MAP_FAILED) { data_ = nullptr; throw Fail("mmap " + path + ": " + strerror(errno)); }
        madvise(data_, size_, MADV_SEQUENTIAL);
    }
    ~Mapped() { if (data_) munmap(data_, size_); if (fd_ >= 0) close(fd_); }
    void * data() const { return data_; }
    size_t size() const { return size_; }
private:
    int fd_ = -1;
    void * data_ = nullptr;
    size_t size_ = 0;
};

class Runtime {
public:
    std::ostringstream details;

    Runtime(const std::string & lib_dir) {
        void * htp = dlopen((lib_dir + "/libQnnHtp.so").c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!htp) throw Fail(std::string("dlopen libQnnHtp.so: ") + dlerror());
        void * sys = dlopen((lib_dir + "/libQnnSystem.so").c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!sys) throw Fail(std::string("dlopen libQnnSystem.so: ") + dlerror());
        // Both libraries stay loaded for the life of the process (see qnn_probe.cpp).

        using GetProviders = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
        auto gp = reinterpret_cast<GetProviders>(dlsym(htp, "QnnInterface_getProviders"));
        if (!gp) throw Fail("QnnInterface_getProviders nenalezeno");
        const QnnInterface_t ** providers = nullptr;
        uint32_t n = 0;
        check(gp(&providers, &n), "QnnInterface_getProviders");
        for (uint32_t i = 0; i < n && !api_; ++i) {
            const auto & v = providers[i]->apiVersion.coreApiVersion;
            if (v.major == QNN_API_VERSION_MAJOR && v.minor >= QNN_API_VERSION_MINOR) api_ = &providers[i]->QNN_INTERFACE_VER_NAME;
        }
        if (!api_) throw Fail("libQnnHtp nemá kompatibilní rozhraní");

        using GetSysProviders = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t ***, uint32_t *);
        auto gsp = reinterpret_cast<GetSysProviders>(dlsym(sys, "QnnSystemInterface_getProviders"));
        if (!gsp) throw Fail("QnnSystemInterface_getProviders nenalezeno");
        const QnnSystemInterface_t ** sproviders = nullptr;
        check(gsp(&sproviders, &n), "QnnSystemInterface_getProviders");
        for (uint32_t i = 0; i < n && !sys_; ++i) {
            const auto & v = sproviders[i]->systemApiVersion;
            if (v.major == QNN_SYSTEM_API_VERSION_MAJOR && v.minor >= QNN_SYSTEM_API_VERSION_MINOR) sys_ = &sproviders[i]->QNN_SYSTEM_INTERFACE_VER_NAME;
        }
        if (!sys_) throw Fail("libQnnSystem nemá kompatibilní rozhraní");

        if (api_->backendGetBuildId) {
            const char * id = nullptr;
            if (api_->backendGetBuildId(&id) == QNN_SUCCESS && id) details << "QNN runtime " << id << "\n";
        }
        {
            std::lock_guard<std::mutex> lock(g_log_mutex);
            g_log.clear();
        }
        try {
            if (api_->logCreate) api_->logCreate(log_cb, QNN_LOG_LEVEL_INFO, &log_);   // INFO: the bring-up steps, for failure reports
            check(api_->backendCreate(log_, nullptr, &backend_), "backendCreate");
            check(api_->deviceCreate(log_, nullptr, &device_), "deviceCreate");
            set_burst();
        } catch (...) {
            release();   // a throwing constructor never runs the destructor
            throw;
        }
    }

    ~Runtime() { release(); }

    // Maps the binary, reads its graph/tensor description, and creates the context on
    // the HTP (which copies the weights into DSP-visible memory).
    void load(Graph & g, const std::string & path) {
        Mapped file(path);
        QnnSystemContext_Handle_t sctx = nullptr;
        check(sys_->systemContextCreate(&sctx), "systemContextCreate");
        try {
            const QnnSystemContext_BinaryInfo_t * info = nullptr;
            Qnn_ContextBinarySize_t info_size = 0;
            check(sys_->systemContextGetBinaryInfo(sctx, file.data(), file.size(), &info, &info_size), "getBinaryInfo");
            uint32_t ngraphs = 0;
            const QnnSystemContext_GraphInfo_t * graphs = nullptr;
            switch (info->version) {
                case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: ngraphs = info->contextBinaryInfoV1.numGraphs; graphs = info->contextBinaryInfoV1.graphs; break;
                case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: ngraphs = info->contextBinaryInfoV2.numGraphs; graphs = info->contextBinaryInfoV2.graphs; break;
                case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: ngraphs = info->contextBinaryInfoV3.numGraphs; graphs = info->contextBinaryInfoV3.graphs; break;
                default: throw Fail("neznámá verze binary info " + std::to_string((int) info->version));
            }
            if (ngraphs < 1 || !graphs) throw Fail(path + ": žádný graf");
            auto take = [&](const auto & gi) {
                g.name = gi.graphName ? gi.graphName : "";
                for (uint32_t i = 0; i < gi.numGraphInputs; ++i) g.inputs.push_back(copy_tensor(gi.graphInputs[i]));
                for (uint32_t i = 0; i < gi.numGraphOutputs; ++i) g.outputs.push_back(copy_tensor(gi.graphOutputs[i]));
            };
            switch (graphs[0].version) {
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1: take(graphs[0].graphInfoV1); break;
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2: take(graphs[0].graphInfoV2); break;
                case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3: take(graphs[0].graphInfoV3); break;
                default: throw Fail("neznámá verze graph info " + std::to_string((int) graphs[0].version));
            }
        } catch (...) {
            sys_->systemContextFree(sctx);
            throw;
        }
        sys_->systemContextFree(sctx);

        const auto t0 = Clock::now();
        check(api_->contextCreateFromBinary(backend_, device_, nullptr, file.data(), file.size(), &g.context, nullptr),
              "contextCreateFromBinary");
        graphs_.push_back(&g);
        check(api_->graphRetrieve(g.context, g.name.c_str(), &g.graph), "graphRetrieve");
        details << path.substr(path.find_last_of('/') + 1) << ": " << file.size() / (1 << 20) << " MB, kontext "
                << (long) ms_since(t0) << " ms\n" << describe(g);
    }

    void execute(const Graph & g) {
        std::vector<Qnn_Tensor_t> in, out;
        in.reserve(g.inputs.size());
        out.reserve(g.outputs.size());
        for (auto & s : g.inputs) in.push_back(s->t);
        for (auto & s : g.outputs) out.push_back(s->t);
        check(api_->graphExecute(g.graph, in.data(), (uint32_t) in.size(), out.data(), (uint32_t) out.size(), nullptr, nullptr),
              "graphExecute");
    }

    // Registers an rpcmem buffer with a context for a tensor of this shape/type.
    Qnn_MemHandle_t mem_register(Qnn_ContextHandle_t ctx, const Slot & s, const Buffer & b) {
        if (!api_->memRegister) throw Fail("QnnMem_register chybí");
        Qnn_MemDescriptor_t d{};
        d.memShape.numDim = (uint32_t) s.dims.size();
        d.memShape.dimSize = const_cast<uint32_t *>(s.dims.data());
        d.memShape.shapeConfig = nullptr;
        d.dataType = s.dtype;
        d.memType = QNN_MEM_TYPE_ION;
        d.ionInfo.fd = b.fd;
        Qnn_MemHandle_t h = nullptr;
        check(api_->memRegister(ctx, &d, 1, &h), ("QnnMem_register " + s.name).c_str());
        return h;
    }

    void mem_deregister(Qnn_MemHandle_t h) { if (h && api_->memDeRegister) api_->memDeRegister(&h, 1); }

    std::string qnn_log() {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        return g_log;
    }

private:
    const QNN_INTERFACE_VER_TYPE * api_ = nullptr;
    const QNN_SYSTEM_INTERFACE_VER_TYPE * sys_ = nullptr;
    Qnn_LogHandle_t log_ = nullptr;
    Qnn_BackendHandle_t backend_ = nullptr;
    Qnn_DeviceHandle_t device_ = nullptr;
    std::vector<Graph *> graphs_;
    const QnnHtpDevice_PerfInfrastructure_t * perf_ = nullptr;
    uint32_t power_id_ = 0;

    void release() {
        for (auto * g : graphs_) if (g->context) { api_->contextFree(g->context, nullptr); g->context = nullptr; }
        graphs_.clear();
        if (perf_ && power_id_) perf_->destroyPowerConfigId(power_id_);
        perf_ = nullptr;
        power_id_ = 0;
        if (device_) api_->deviceFree(device_);
        device_ = nullptr;
        if (backend_) api_->backendFree(backend_);
        backend_ = nullptr;
        if (log_ && api_->logFree) api_->logFree(log_);
        log_ = nullptr;
    }

    // "Burst" vote as in Qualcomm's sample apps: DCVS off, highest voltage corners, short
    // RPC latency. Without it the HTP may sit at low clocks and the timings mean little.
    void set_burst() {
        if (!api_->deviceGetInfrastructure) { details << "výkonový profil: nedostupný\n"; return; }
        QnnDevice_Infrastructure_t infra = nullptr;
        if (api_->deviceGetInfrastructure(&infra) != QNN_SUCCESS || !infra) { details << "výkonový profil: getInfrastructure selhalo\n"; return; }
        auto * htp = reinterpret_cast<QnnHtpDevice_Infrastructure_t *>(infra);
        if (htp->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) { details << "výkonový profil: není PERF\n"; return; }
        perf_ = &htp->perfInfra;
        if (perf_->createPowerConfigId(0, 0, &power_id_) != QNN_SUCCESS) { perf_ = nullptr; details << "výkonový profil: createPowerConfigId selhalo\n"; return; }

        QnnHtpPerfInfrastructure_PowerConfig_t dcvs{};
        dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
        auto & d = dcvs.dcvsV3Config;
        d.contextId = power_id_;
        d.setDcvsEnable = 1; d.dcvsEnable = 0;
        d.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
        d.setSleepLatency = 1; d.sleepLatency = 40;
        d.setSleepDisable = 1; d.sleepDisable = 1;
        d.setBusParams = 1;
        d.busVoltageCornerMin = d.busVoltageCornerTarget = d.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        d.setCoreParams = 1;
        d.coreVoltageCornerMin = d.coreVoltageCornerTarget = d.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        QnnHtpPerfInfrastructure_PowerConfig_t latency{};
        latency.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
        latency.rpcControlLatencyConfig = 100;
        QnnHtpPerfInfrastructure_PowerConfig_t polling{};
        polling.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
        polling.rpcPollingTimeConfig = 9999;
        const QnnHtpPerfInfrastructure_PowerConfig_t * cfg[] = {&dcvs, &latency, &polling, nullptr};
        const Qnn_ErrorHandle_t e = perf_->setPowerConfig(power_id_, cfg);
        details << "výkonový profil burst: " << (e == QNN_SUCCESS ? "OK" : "chyba " + hex(e)) << "\n";
    }
};

struct RunOut {
    double full_ms = 0, mel_ms = 0, encode_ms = 0, decode_ms = 0, sample_ms = 0;
    int encode_runs = 0, decode_runs = 0, lang_token = -1, loop_cuts = 0;
    std::vector<int> tokens;
    bool aborted = false;
};

class Whisper {
public:
    // shared = rpcmem buffers registered with QNN (zero-copy); otherwise heap client buffers.
    Whisper(Runtime & rt, Graph & enc, Graph & dec, const RpcMem * rpc) : rt_(rt), enc_(enc), dec_(dec), rpc_(rpc) {
        try {
            setup();
        } catch (...) {
            release();
            throw;
        }
    }

    ~Whisper() { release(); }

    bool shared() const { return rpc_ != nullptr; }

    RunOut transcribe(const float * pcm, size_t n, int lang_token, int threads, RunToken & token) {
        RunOut r;
        const auto t_full = Clock::now();
        const size_t chunks = std::max<size_t>(1, (n + kChunkSamples - 1) / kChunkSamples);
        int lang = lang_token;
        for (size_t c = 0; c < chunks && !r.aborted; ++c) {
            const size_t off = c * kChunkSamples;
            const size_t len = std::min<size_t>(kChunkSamples, n - std::min(n, off));
            auto t0 = Clock::now();
            const std::vector<float> mel = whisper_log_mel(pcm + off, (int) len, threads);
            put_floats(*mel_, *mel_buf_, mel.data(), mel.size());
            r.mel_ms += ms_since(t0);

            t0 = Clock::now();
            rt_.execute(enc_);
            r.encode_ms += ms_since(t0);
            r.encode_runs++;

            decode_window(r, lang, token);
            if (r.lang_token < 0) r.lang_token = lang;
            token.progress = (int) ((c + 1) * 100 / chunks);
        }
        r.full_ms = ms_since(t_full);
        return r;
    }

private:
    struct SelfCache { Slot * in; Slot * out; Buffer * a; Buffer * b; };

    Runtime & rt_;
    Graph & enc_;
    Graph & dec_;
    const RpcMem * rpc_;
    std::vector<std::unique_ptr<Buffer>> bufs_;
    std::map<std::pair<Qnn_ContextHandle_t, const Buffer *>, Qnn_MemHandle_t> handles_;
    Slot *mel_ = nullptr, *ids_ = nullptr, *pos_ = nullptr, *mask_ = nullptr, *logits_ = nullptr;
    Buffer *mel_buf_ = nullptr, *ids_buf_ = nullptr, *pos_buf_ = nullptr, *mask_buf_ = nullptr, *logits_buf_ = nullptr;
    std::vector<SelfCache> self_;
    std::vector<uint8_t> suppressed_;
    size_t n_vocab_ = 0;

    static Slot * need(Slot * s, const char * what) { if (!s) throw Fail(std::string("graf nemá tenzor ") + what); return s; }

    // dsp_only: the CPU never reads or writes it during inference (KV caches), so it can be
    // uncached and FastRPC has no CPU cache lines to maintain for it on each call.
    Buffer * alloc(size_t bytes, bool dsp_only = false) {
        auto b = std::make_unique<Buffer>();
        b->bytes = bytes;
        if (rpc_) {
            b->data = static_cast<uint8_t *>(rpc_->alloc(RpcMem::kHeapIdSystem, dsp_only ? RpcMem::kUncached : RpcMem::kDefaultFlags, (int) bytes));
            if (!b->data) throw Fail("rpcmem_alloc " + std::to_string(bytes) + " B selhalo");
            b->fd = rpc_->to_fd(b->data);
            if (b->fd < 0) { rpc_->free(b->data); throw Fail("rpcmem_to_fd selhalo"); }
        } else {
            b->data = new uint8_t[bytes];
        }
        memset(b->data, 0, bytes);
        bufs_.push_back(std::move(b));
        return bufs_.back().get();
    }

    // Points a graph tensor at a buffer; shared buffers are registered once per context.
    void bind(const Graph & g, Slot * s, Buffer * b) {
        if (b->bytes < s->bytes) throw Fail("buffer pro " + s->name + " je malý");
        if (!rpc_) { s->set_buffer(b->data); return; }
        auto key = std::make_pair(g.context, (const Buffer *) b);
        auto it = handles_.find(key);
        if (it == handles_.end()) it = handles_.emplace(key, rt_.mem_register(g.context, *s, *b)).first;
        s->set_handle(it->second);
    }

    void setup() {
        // Encoder outputs feed the decoder's cross inputs directly: one buffer, two contexts.
        for (auto & s : enc_.outputs) {
            Slot * d = dec_.in(s->name);
            if (!d) throw Fail("dekodér nemá vstup " + s->name);
            if (d->bytes != s->bytes || d->dtype != s->dtype) throw Fail("nesoulad tvaru/typu " + s->name);
            Buffer * b = alloc(s->bytes, true);
            bind(enc_, s.get(), b);
            bind(dec_, d, b);
        }
        mel_ = need(enc_.in("input_features"), "input_features");
        ids_ = need(dec_.in("input_ids"), "input_ids");
        pos_ = need(dec_.in("position_ids"), "position_ids");
        mask_ = need(dec_.in("attention_mask"), "attention_mask");
        logits_ = need(dec_.out("logits"), "logits");
        if (ids_->dtype != QNN_DATATYPE_INT_32 || pos_->dtype != QNN_DATATYPE_INT_32) throw Fail("input_ids/position_ids nejsou int32");
        bind(enc_, mel_, mel_buf_ = alloc(mel_->bytes));
        bind(dec_, ids_, ids_buf_ = alloc(ids_->bytes));
        bind(dec_, pos_, pos_buf_ = alloc(pos_->bytes));
        bind(dec_, mask_, mask_buf_ = alloc(mask_->bytes));
        bind(dec_, logits_, logits_buf_ = alloc(logits_->bytes));
        n_vocab_ = logits_->bytes / dtype_size(logits_->dtype);
        for (int i = 0;; ++i) {
            Slot * ki = dec_.in("k_cache_self_" + std::to_string(i) + "_in");
            if (!ki) break;
            Slot * vi = need(dec_.in("v_cache_self_" + std::to_string(i) + "_in"), "v_cache_self_in");
            Slot * ko = need(dec_.out("k_cache_self_" + std::to_string(i) + "_out"), "k_cache_self_out");
            Slot * vo = need(dec_.out("v_cache_self_" + std::to_string(i) + "_out"), "v_cache_self_out");
            self_.push_back({ki, ko, nullptr, nullptr});
            self_.push_back({vi, vo, nullptr, nullptr});
        }
        if (self_.empty()) throw Fail("dekodér nemá self-attention cache");
        for (auto & p : self_) {
            if (p.in->bytes != p.out->bytes || p.in->dims != p.out->dims) throw Fail("self cache in/out nesouhlasí: " + p.in->name);
            p.a = alloc(p.in->bytes, true);
            p.b = alloc(p.in->bytes, true);
            // Both buffers serve as input and output (they swap every step); in and out
            // have the same shape, so one registration per buffer covers both roles.
            bind(dec_, p.in, p.a);
            bind(dec_, p.out, p.b);
        }
        suppressed_.assign(n_vocab_, 0);
        for (size_t t = kEot + 1; t < n_vocab_; ++t) suppressed_[t] = 1;   // specials + timestamps
        for (int t : kSuppress) suppressed_[t] = 1;
    }

    void release() {
        for (auto & h : handles_) rt_.mem_deregister(h.second);
        handles_.clear();
        for (auto & b : bufs_) {
            if (!b->data) continue;
            if (b->fd >= 0) rpc_->free(b->data); else delete[] b->data;
            b->data = nullptr;
        }
        bufs_.clear();
    }

    // Greedy decoding has no temperature fallback, so a hallucination loop would run to the
    // 196-token limit. When the window ends in the same k-gram repeated (k 1..32: 8x for
    // k = 1, 4x for k = 2..3, 3x for longer), keep one copy and stop the window.
    static bool cut_loop(std::vector<int> & window, std::vector<int> & all) {
        const size_t n = window.size();
        for (size_t k = 1; k <= 32; ++k) {
            const size_t reps = k == 1 ? 8 : k <= 3 ? 4 : 3;
            if (n < k * reps) break;
            bool same = true;
            for (size_t i = 0; i < k * (reps - 1) && same; ++i) same = window[n - 1 - i] == window[n - 1 - i - k];
            if (!same) continue;
            const size_t drop = k * (reps - 1);
            window.resize(n - drop);
            all.resize(all.size() - drop);
            return true;
        }
        return false;
    }

    // Greedy decode of one window: prompt <|sot|> <|lang|> <|transcribe|> <|notimestamps|>,
    // language picked from the logits after <|sot|> when not given (lang < 0).
    void decode_window(RunOut & r, int & lang, RunToken & token) {
        for (auto & p : self_) {
            memset(p.a->data, 0, p.a->bytes);
            bind(dec_, p.in, p.a);
            bind(dec_, p.out, p.b);
        }
        const size_t mask_n = mask_->bytes / dtype_size(mask_->dtype);
        std::vector<float> mask(mask_n, kMaskNeg);
        std::vector<int> seq = {kSot};
        const int prompt[] = {kSot, lang, kTranscribe, kNoTimestamps};
        const int prompt_len = 4;
        bool first_generated = true;
        std::vector<int> window;   // tokens generated in this window

        for (int n = 0; n < kDecodeLen - 1; ++n) {
            if (token.abort.load()) { r.aborted = true; return; }
            auto t0 = Clock::now();
            reinterpret_cast<int32_t *>(ids_buf_->data)[0] = seq[n];
            reinterpret_cast<int32_t *>(pos_buf_->data)[0] = n;
            mask[mask_n - 1 - n] = 0.0f;
            put_floats(*mask_, *mask_buf_, mask.data(), mask_n);
            rt_.execute(dec_);
            r.decode_ms += ms_since(t0);
            r.decode_runs++;
            // This step's output cache is the next step's input.
            for (auto & p : self_) {
                std::swap(p.a, p.b);
                bind(dec_, p.in, p.a);
                bind(dec_, p.out, p.b);
            }

            t0 = Clock::now();
            const Buffer & lg = *logits_buf_;
            int next;
            if (n + 1 < prompt_len) {
                next = prompt[n + 1];
                if (next < 0) {   // language detection
                    next = kLangFirst;
                    float best = get_float(*logits_, lg, kLangFirst);
                    for (int t = kLangFirst + 1; t < kLangEnd; ++t) {
                        const float v = get_float(*logits_, lg, t);
                        if (v > best) { best = v; next = t; }
                    }
                    lang = next;
                }
            } else {
                next = -1;
                float best = -1e30f;
                for (size_t t = 0; t < n_vocab_; ++t) {
                    if (suppressed_[t]) continue;
                    if (first_generated && (t == 220 || t == (size_t) kEot)) continue;
                    const float v = get_float(*logits_, lg, t);
                    if (v > best) { best = v; next = (int) t; }
                }
                first_generated = false;
            }
            r.sample_ms += ms_since(t0);
            if (n + 1 >= prompt_len) {
                if (next == kEot || next < 0) return;
                r.tokens.push_back(next);
                window.push_back(next);
                if (cut_loop(window, r.tokens)) { r.loop_cuts++; return; }
            }
            seq.push_back(next);
        }
    }
};

// Loaded model kept between transcriptions. Member order matters: destroyed in reverse,
// so the Whisper buffers are deregistered before the runtime frees the contexts, and the
// graphs' tensor slots outlive both.
struct Engine {
    Graph enc, dec;
    std::unique_ptr<Runtime> rt;
    RpcMem rpc;
    std::unique_ptr<Whisper> w;
    std::mutex mutex;   // QNN graphs are not re-entrant
    std::string info;
};

} // namespace

#endif // WB_HAVE_QNN

} // namespace wb

// --- JNI: cz.vyvrnc.transcribronpu.npu.NpuNative ----------------------------------------

#include <jni.h>

namespace {

void throw_java(JNIEnv * env, const char * msg) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, msg);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

#define NPU_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_cz_vyvrnc_transcribronpu_npu_NpuNative_##name

// Loads both context binaries; returns a handle, throws IllegalStateException with the
// reason (and the QNN log) on failure.
NPU_FN(jlong, open)(JNIEnv * env, jclass, jstring libDir, jstring encoderPath, jstring decoderPath) {
#ifndef WB_HAVE_QNN
    throw_java(env, "Aplikace byla sestavena bez hlaviček QNN");
    return 0;
#else
    using namespace wb;
    auto e = std::make_unique<Engine>();
    try {
        e->rt = std::make_unique<Runtime>(jstr(env, libDir));
        e->rt->load(e->enc, jstr(env, encoderPath));
        e->rt->load(e->dec, jstr(env, decoderPath));
        if (e->rpc.load()) {
            try {
                e->w = std::make_unique<Whisper>(*e->rt, e->enc, e->dec, &e->rpc);
            } catch (const Fail & f) {
                e->rt->details << "sdílená paměť nejde (" << f.what() << "), běží s kopiemi\n";
            }
        }
        if (!e->w) e->w = std::make_unique<Whisper>(*e->rt, e->enc, e->dec, nullptr);
        e->info = e->rt->details.str();
        return reinterpret_cast<jlong>(e.release());
    } catch (const std::exception & ex) {
        std::string msg = ex.what();
        e.reset();
        // Read after reset: the runtime may never have existed (its constructor threw), and
        // freeing it can log too. The log is global.
        { std::lock_guard<std::mutex> lock(g_log_mutex); if (!g_log.empty()) msg += "\nQNN log:\n" + g_log; }
        throw_java(env, msg.c_str());
        return 0;
    }
#endif
}

// Returns [lang_token, loop_cuts, text tokens...]; langToken -1 detects the language.
NPU_FN(jintArray, transcribe)(JNIEnv * env, jclass, jlong handle, jfloatArray pcm, jint langToken, jint threads) {
#ifndef WB_HAVE_QNN
    throw_java(env, "Aplikace byla sestavena bez hlaviček QNN");
    return nullptr;
#else
    using namespace wb;
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e || !pcm) { throw_java(env, "NPU engine není načtený"); return nullptr; }
    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples((size_t) n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    try {
        std::lock_guard<std::mutex> lock(e->mutex);
        RunToken token;
        const RunOut r = e->w->transcribe(samples.data(), samples.size(), langToken, threads, token);
        std::vector<jint> out;
        out.reserve(r.tokens.size() + 2);
        out.push_back(r.lang_token);
        out.push_back(r.loop_cuts);
        for (int t : r.tokens) out.push_back(t);
        jintArray arr = env->NewIntArray((jsize) out.size());
        if (arr) env->SetIntArrayRegion(arr, 0, (jsize) out.size(), out.data());
        return arr;
    } catch (const std::exception & ex) {
        throw_java(env, ex.what());
        return nullptr;
    }
#endif
}

NPU_FN(jstring, info)(JNIEnv * env, jclass, jlong handle) {
#ifdef WB_HAVE_QNN
    auto * e = reinterpret_cast<wb::Engine *>(handle);
    if (e) return env->NewStringUTF(e->info.c_str());
#endif
    return env->NewStringUTF("");
}

NPU_FN(void, close)(JNIEnv *, jclass, jlong handle) {
#ifdef WB_HAVE_QNN
    auto * e = reinterpret_cast<wb::Engine *>(handle);
    if (!e) return;
    { std::lock_guard<std::mutex> lock(e->mutex); }   // let a running transcription finish
    delete e;
#endif
}
