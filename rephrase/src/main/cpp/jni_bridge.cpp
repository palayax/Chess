// JNI bridge of librephrase.so (C2, docs/LLM_REPHRASE_DESIGN.md §2.2): one llama.cpp model and context per handle,
// greedy decoding, the fixed prompt prefix kept in the KV cache between calls.
//
// Contract with NativeRephrase.kt (R8 keeps that class and its natives: rephrase/consumer-rules.pro):
//   nativeLoad(path, nThreads, nCtx)                     -> handle, 0 when the file cannot be loaded
//   nativeFree(handle)
//   nativeComplete(handle, prefix, suffix, maxTokens)    -> UTF-8 bytes of the generated text, null on failure
//   nativeCancel(handle)                                 -> the running completion stops at the next token
//   nativeTokenCount(handle, text)                       -> tokens of text (no special parsing), -1 on failure
//   nativeLastStats(handle)                              -> [prefixTokens, prefixReused, promptTokens, promptMicros,
//                                                            genTokens, genMicros]
// Calls on one handle must not overlap (the Kotlin side holds a mutex); nativeCancel may come from any thread.
// The C++ side never calls back into Java.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "rephrase"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    std::vector<llama_token> cached_prefix;  // what seq 0 holds from position 0 (the fixed prompt prefix)
    std::atomic<bool> cancel{false};
    int64_t stats[6] = {0, 0, 0, 0, 0, 0};
};

std::once_flag g_backend_once;

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

void abort_callback(const char * message) {
    // ggml aborts the process after this; the Kotlin side's load journal turns that into "damaged" at the next
    // start (design §6.4). Logged so the reason is in logcat.
    __android_log_print(ANDROID_LOG_FATAL, TAG, "ggml abort: %s", message ? message : "(null)");
}

bool abort_check(void * data) {
    return static_cast<Session *>(data)->cancel.load();
}

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string to_utf8(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    // GetStringUTFChars gives modified UTF-8; go through getBytes("UTF-8") semantics by hand instead.
    const jsize len = env->GetStringLength(s);
    const jchar * chars = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(len);
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

bool tokenize(const llama_vocab * vocab, const std::string & text, bool parse_special, std::vector<llama_token> & out) {
    int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, false, parse_special);
    if (n <= 0) {
        out.clear();
        return n == 0 || text.empty();
    }
    out.resize(n);
    const int got = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), out.data(), n, false, parse_special);
    if (got < 0) return false;
    out.resize(got);
    return true;
}

// Decodes tokens[from, to) at positions starting at pos0, in batches; logits only for the very last token.
bool decode_range(Session * s, const std::vector<llama_token> & tokens, size_t from, size_t to, int pos0) {
    llama_batch batch = llama_batch_init(s->n_batch, 0, 1);
    bool ok = true;
    for (size_t i = from; i < to && ok; i += s->n_batch) {
        const size_t end = std::min(to, i + static_cast<size_t>(s->n_batch));
        batch.n_tokens = 0;
        for (size_t j = i; j < end; ++j) {
            const int k = batch.n_tokens++;
            batch.token[k] = tokens[j];
            batch.pos[k] = pos0 + static_cast<int>(j - from);
            batch.n_seq_id[k] = 1;
            batch.seq_id[k][0] = 0;
            batch.logits[k] = (j + 1 == to) ? 1 : 0;
        }
        const int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            LOGW("llama_decode returned %d", rc);
            ok = false;
        }
    }
    llama_batch_free(batch);
    return ok;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint n_threads, jint n_ctx) {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_callback, nullptr);
        ggml_set_abort_callback(abort_callback);
        llama_backend_init();
    });
    const std::string path = to_utf8(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // the 1.1 GB file is paged in, not copied (file-backed for the LMK)
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return 0;
    }

    auto * s = new Session();
    s->model = model;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = n_ctx;
    s->n_batch = 512;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(s->n_batch);
    cp.n_ubatch = static_cast<uint32_t>(s->n_batch);
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.no_perf = true;
    s->ctx = llama_init_from_model(model, cp);
    if (s->ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        delete s;
        return 0;
    }
    llama_set_abort_callback(s->ctx, abort_check, s);

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    s->sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(s->sampler, llama_sampler_init_greedy());

    LOGI("model loaded: ctx %d, threads %d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

JNIEXPORT void JNICALL
Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_nativeTokenCount(JNIEnv * env, jobject, jlong handle, jstring jtext) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return -1;
    std::vector<llama_token> tokens;
    if (!tokenize(s->vocab, to_utf8(env, jtext), false, tokens)) return -1;
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jlongArray JNICALL
Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_nativeLastStats(JNIEnv * env, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    jlongArray out = env->NewLongArray(6);
    if (s != nullptr && out != nullptr) {
        jlong v[6];
        for (int i = 0; i < 6; ++i) v[i] = s->stats[i];
        env->SetLongArrayRegion(out, 0, 6, v);
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_nativeComplete(
        JNIEnv * env, jobject, jlong handle, jstring jprefix, jstring jsuffix, jint max_tokens) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return nullptr;
    s->cancel.store(false);
    for (auto & v : s->stats) v = 0;

    std::vector<llama_token> prefix, suffix;
    if (!tokenize(s->vocab, to_utf8(env, jprefix), true, prefix) || !tokenize(s->vocab, to_utf8(env, jsuffix), true, suffix)) {
        LOGW("tokenize failed");
        return nullptr;
    }
    if (static_cast<int>(prefix.size() + suffix.size()) + max_tokens + 1 > s->n_ctx) {
        LOGW("prompt (%zu + %zu) + %d tokens does not fit n_ctx %d", prefix.size(), suffix.size(), max_tokens, s->n_ctx);
        return nullptr;
    }
    llama_memory_t mem = llama_get_memory(s->ctx);
    const int64_t t0 = now_us();

    // The fixed prefix: reuse it when the cache holds exactly these tokens, otherwise rebuild it.
    const bool reuse = !s->cached_prefix.empty() && s->cached_prefix == prefix;
    if (reuse) {
        llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(prefix.size()), -1);
    } else {
        llama_memory_clear(mem, true);
        s->cached_prefix.clear();
        // Logits are not needed for the prefix alone; decode_range asks for the last token's, which is harmless.
        if (!decode_range(s, prefix, 0, prefix.size(), 0)) {
            llama_memory_clear(mem, true);
            return nullptr;
        }
        s->cached_prefix = prefix;
    }
    if (!decode_range(s, suffix, 0, suffix.size(), static_cast<int>(prefix.size()))) {
        // A failed or aborted decode may leave part of the suffix behind: start from a clean prefix next time.
        llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(prefix.size()), -1);
        return nullptr;
    }
    const int64_t t1 = now_us();

    std::string out;
    int pos = static_cast<int>(prefix.size() + suffix.size());
    int generated = 0;
    llama_sampler_reset(s->sampler);
    llama_batch one = llama_batch_init(1, 0, 1);
    bool failed = false;
    char piece[256];
    while (generated < max_tokens) {
        if (s->cancel.load()) { failed = true; break; }
        const llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        const int n = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        ++generated;
        // Stop rules shared with the host measurement: a blank line ends the answer.
        if (out.find("\n\n") != std::string::npos) break;
        one.n_tokens = 1;
        one.token[0] = tok;
        one.pos[0] = pos++;
        one.n_seq_id[0] = 1;
        one.seq_id[0][0] = 0;
        one.logits[0] = 1;
        if (llama_decode(s->ctx, one) != 0) { failed = true; break; }
    }
    llama_batch_free(one);
    const int64_t t2 = now_us();
    // Leave only the prefix in the cache for the next call.
    llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(prefix.size()), -1);

    s->stats[0] = static_cast<int64_t>(prefix.size());
    s->stats[1] = reuse ? 1 : 0;
    s->stats[2] = static_cast<int64_t>(suffix.size());
    s->stats[3] = t1 - t0;
    s->stats[4] = generated;
    s->stats[5] = t2 - t1;
    if (failed) return nullptr;

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

}  // extern "C"
