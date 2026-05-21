#include "LlamaCppBackend.h"
#include "ModeStore.h"
#include "PromptPreFilter.h"
#include "StyleStore.h"
#include "SystemPromptBuilder.h"

#include <llama.h>
#include <QByteArray>
#include <QDebug>

#include <atomic>
#include <exception>
#include <string>
#include <vector>

namespace localyze {

// Per-instance re-entrancy guard. If generate()/generateRaw() is invoked while
// a prior decodePrompt() is still running on another (or the same) worker
// thread, the inner state of llama_decode/llama_batch_get_one (which uses
// thread-unsafe static storage in some llama.cpp builds) can be corrupted —
// a likely root cause of the SIGSEGV cluster on rapid back-to-back turns.
// We use an atomic flag (`generating_`) on the backend instance plus an RAII
// guard so the check itself is race-free and always cleared on exit.
namespace {
struct DecodingGuard {
    std::atomic<bool>& flag;
    bool owned{false};
    explicit DecodingGuard(std::atomic<bool>& f) : flag(f) {
        bool expected = false;
        owned = flag.compare_exchange_strong(expected, true);
    }
    ~DecodingGuard() { if (owned) flag.store(false); }
};
}  // namespace

LlamaCppBackend::LlamaCppBackend(BackendKind kind, QString gguPath, QObject* parent)
    : QObject(parent), kind_(kind), gguPath_(std::move(gguPath)) {
    llama_backend_init();
    // Lazy: built on first generate() so the date stamp is current per turn.
}

int LlamaCppBackend::recommendedContext(int vramGB, int ramGB) {
    // The model itself supports 128K tokens. The KV cache scales with n_ctx,
    // so the practical ceiling is GPU memory. Pick the biggest power-of-two
    // step that fits comfortably alongside model weights (~4 GB for Q4 E4B).
    //
    //   ≤  3 GB VRAM (or CPU-only on small box) → 2048
    //      4–6 GB                                → 4096
    //      7–10 GB                                → 8192
    //     11–14 GB                                → 16384
    //     15–22 GB                                → 32768
    //     23+  GB                                 → 65536
    //
    // For CPU-only runs we cap at 8192 regardless of RAM — bigger contexts
    // are too slow to be useful without GPU offload.
    if (vramGB <= 0) {
        return ramGB >= 32 ? 8192 : (ramGB >= 16 ? 4096 : 2048);
    }
    if (vramGB <=  3) return 2048;
    if (vramGB <=  6) return 4096;
    if (vramGB <= 10) return 8192;
    if (vramGB <= 14) return 16384;
    if (vramGB <= 22) return 32768;
    return 65536;
}

LlamaCppBackend::~LlamaCppBackend() {
    if (sampler_) llama_sampler_free(sampler_);
    if (ctx_)     llama_free(ctx_);
    if (model_)   llama_model_free(model_);
    llama_backend_free();
}

QString LlamaCppBackend::load(int nGpuLayers, int contextSize) {
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;     // 0 → CPU; large → all layers on GPU
    model_ = llama_model_load_from_file(gguPath_.toUtf8().constData(), mparams);
    if (!model_) return QStringLiteral("failed to load GGUF: %1").arg(gguPath_);

    auto cparams = llama_context_default_params();
    cparams.n_ctx       = contextSize;
    // n_batch was 512 — but our system prompt is ~3000 tokens (Claude-style
    // quality rules section is long), and llama-context.cpp:1599 asserts
    // n_tokens_all <= n_batch. With 512, the very first prefill ABORT()s
    // inside ggml. Bump to 4096: large enough for any reasonable system
    // prompt + user turn, well within VRAM budget on a 12 GB GPU.
    cparams.n_batch     = 4096;
    cparams.n_threads   = QThread::idealThreadCount();
    cparams.n_threads_batch = cparams.n_threads;
    ctx_ = llama_init_from_model(model_, cparams);
    if (!ctx_) return QStringLiteral("failed to create llama context");
    // Flush any latent Vulkan / compute-backend setup work the context init
    // dispatched asynchronously. Without this the first user-triggered decode
    // can race against partially-initialized compute pipelines on some Vulkan
    // drivers and SIGSEGV mid-prefill.
    llama_synchronize(ctx_);

    // Sampler chain — same defaults the mobile app uses.
    auto sparams = llama_sampler_chain_default_params();
    sampler_ = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler_, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler_, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler_, llama_sampler_init_temp(0.4f));
    llama_sampler_chain_add(sampler_, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return {};
}

void LlamaCppBackend::stop() { stopFlag_.store(true); }

void LlamaCppBackend::generate(QString prompt, int maxTokens) {
    if (!ctx_ || !model_) { emit failed("backend not loaded"); return; }
    stopFlag_.store(false);

    // Pre-filter: intercept obvious prompt-injection and vague openers with
    // canned safe responses so the model never sees them. Mirrors the eval
    // harness so desktop runtime behavior matches eval-time behavior.
    const auto pre = PromptPreFilter::decide(prompt);
    if (!pre.passthrough) {
        emit tokenStream(pre.response);
        emit finished();
        return;
    }

    // Pick the user-selected Capability Mode at the start of every turn so
    // the model receives the right tailored system prompt without restarting.
    // The override path (used by e.g. ReActAgent) still wins — those flows
    // pre-build a multi-turn framing that must not be replaced.
    const auto mode = ModeStore::instance().resolvedMode();
    QString sys = systemPromptOverride_.isEmpty()
        ? SystemPromptBuilder::build(mode, /*enableThinking=*/false)
        : systemPromptOverride_;
    // Append the user-selected response Style as a one-line addendum so the
    // model adjusts tone/length without rebuilding the per-mode system prompt.
    const QString styleAdd = StyleStore::instance().styleAddendum();
    if (!styleAdd.isEmpty()) {
        sys += styleAdd;
        qInfo().noquote() << "[StylePicker] style="
                          << StyleStore::instance().currentStyle();
    }
    if (mode != SystemPromptBuilder::Mode::Chat) {
        qInfo().noquote() << "[ModePicker] mode="
                          << ModeStore::instance().currentMode()
                          << " sys[0..80]=" << sys.left(80);
    }
    const QString templated = SystemPromptBuilder::wrapGemma(prompt, sys);
    decodePrompt(templated, maxTokens);
}

void LlamaCppBackend::generateRaw(QString prompt, int maxTokens) {
    if (!ctx_ || !model_) { emit failed("backend not loaded"); return; }
    stopFlag_.store(false);
    decodePrompt(prompt, maxTokens);
}

void LlamaCppBackend::decodePrompt(const QString& templated, int maxTokens) {
    // Re-entrancy guard: bail immediately if another decode is in flight.
    // ReActAgent multi-step flows can re-enter generate() before the prior
    // turn's decode loop has actually returned (the worker thread is blocked
    // inside llama_decode), which races stopFlag_ and corrupts the static
    // storage backing llama_batch_get_one in some llama.cpp builds.
    DecodingGuard guard(generating_);
    if (!guard.owned) {
        emit failed("backend busy");
        return;
    }

    // Wrap the whole decode in a try/catch: llama.cpp can throw on malformed
    // tokens, KV exhaustion, or ggml backend faults. An uncaught C++ exception
    // crossing back into Qt's event loop terminates the process — exactly the
    // SIGABRT pattern we saw in the 67-crash run.
    try {
        // Fully flush any in-flight decode from the previous turn before we
        // touch the KV cache. Without this, llama_memory_clear can race with a
        // still-pending llama_decode on the compute backend and crash on rapid
        // back-to-back prompts.
        llama_synchronize(ctx_);

        // Reset the KV cache between independent turns. Clear metadata only
        // (`data=false`) so we don't tear down the underlying buffers — clearing
        // with data=true was reliably causing a SIGSEGV after a handful of turns,
        // probably a use-after-free in ggml's sequence pool.
        //
        // SKIP on the very first turn — there's nothing decoded yet, so there's
        // nothing to clear, and on Vulkan the clear-before-first-decode path
        // has been observed to SIGSEGV (it touches compute-queue state that
        // hasn't been primed by a real decode yet).
        if (!first_decode_.load()) {
            llama_memory_clear(llama_get_memory(ctx_), /*data=*/false);
        } else {
            qInfo() << "[LlamaCppBackend] first decode — skipping pre-clear";
        }

        const auto vocab = llama_model_get_vocab(model_);
        if (!vocab) { emit failed("vocab unavailable"); return; }
        const int vocab_size = llama_vocab_n_tokens(vocab);
        const QByteArray promptBytes = templated.toUtf8();

        std::vector<llama_token> tokens;
        tokens.resize(promptBytes.size() + 64);
        int n = llama_tokenize(vocab, promptBytes.constData(), promptBytes.size(),
                               tokens.data(), tokens.size(), /*add_special=*/true,
                               /*parse_special=*/true);
        if (n < 0) {
            tokens.resize(-n);
            n = llama_tokenize(vocab, promptBytes.constData(), promptBytes.size(),
                               tokens.data(), tokens.size(), true, true);
        }
        if (n <= 0) { emit failed("tokenize failed"); return; }
        tokens.resize(n);

        // Forensics: log token count + first few IDs so if the next call
        // SIGSEGVs inside llama_decode we have a record of exactly what we
        // submitted to the compute backend.
        if (n >= 3) {
            qInfo() << "[LlamaCppBackend] prefill n=" << n
                    << " first3=" << tokens[0] << tokens[1] << tokens[2];
        } else {
            qInfo() << "[LlamaCppBackend] prefill n=" << n << " (short prompt)";
        }
        // Chunked prefill: llama-context asserts n_tokens_all <= n_batch
        // (currently 4096). Even with the bumped batch size, very long
        // prompts could exceed it — so chunk the prefill into n_batch-sized
        // pieces and submit them sequentially. The KV cache concatenates
        // them, then sampling kicks in on the last token.
        constexpr int kPrefillChunk = 2048;  // well under n_batch=4096
        llama_synchronize(ctx_);
        for (int off = 0; off < n; off += kPrefillChunk) {
            const int chunk = std::min(kPrefillChunk, n - off);
            llama_batch batch = llama_batch_get_one(tokens.data() + off, chunk);
            if (llama_decode(ctx_, batch) != 0) {
                emit failed(QString("prefill failed at offset %1 (chunk %2)")
                                .arg(off).arg(chunk));
                return;
            }
        }
        llama_synchronize(ctx_);
        // First decode completed successfully — future turns may now clear KV.
        first_decode_.store(false);

        llama_token next = 0;
        for (int i = 0; i < maxTokens && !stopFlag_.load(); ++i) {
            if (!sampler_) { emit failed("sampler unavailable"); return; }
            next = llama_sampler_sample(sampler_, ctx_, -1);
            // Defensive bounds check: a sampler returning a token ID outside
            // the vocab range would walk off the end of internal piece tables
            // in llama_token_to_piece. Skip and break rather than crash.
            if (next < 0 || (vocab_size > 0 && next >= vocab_size)) {
                qWarning() << "[LlamaCppBackend] sampler returned out-of-range token"
                           << next << "vocab_size=" << vocab_size << "— stopping";
                break;
            }
            if (llama_vocab_is_eog(vocab, next)) break;

            // Bumped 256 → 1024: some Gemma 3n chat-template specials and rare
            // multi-byte tokens with special=true exceed 256 bytes, which
            // previously truncated (len negative) or, worse, allowed a write
            // past the stack buffer in older llama.cpp builds where the
            // returned length wasn't clamped before the copy.
            char buf[1024];
            int len = llama_token_to_piece(vocab, next, buf, sizeof(buf), 0, /*special=*/true);
            if (len < 0) {
                // Token didn't fit even in 1024 bytes — skip rather than crash.
                qWarning() << "[LlamaCppBackend] token_to_piece overflow, skipping token" << next;
                continue;
            }
            if (len > static_cast<int>(sizeof(buf))) len = sizeof(buf);  // defensive clamp
            emit tokenStream(QString::fromUtf8(buf, len));

            llama_batch one = llama_batch_get_one(&next, 1);
            if (llama_decode(ctx_, one) != 0) { emit failed("decode failed"); return; }
        }
        // Drain pending compute before signalling finished so the next turn
        // (which may arrive almost immediately on a back-to-back feeder run)
        // starts with a quiescent backend.
        llama_synchronize(ctx_);
        emit finished();
    } catch (const std::exception& e) {
        qWarning() << "[LlamaCppBackend] std::exception in decode loop:" << e.what();
        emit failed(QString("decode exception: %1").arg(QString::fromUtf8(e.what())));
    } catch (...) {
        qWarning() << "[LlamaCppBackend] unknown exception in decode loop";
        emit failed("decode exception: unknown");
    }
}

}  // namespace localyze
