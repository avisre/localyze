# Localyze Desktop Architecture

## Goals

- **Native UX per OS** — no abstraction tax, no cross-platform shell.
- **Same model as mobile** — Gemma 4 E4B, identical weights, just packaged in each platform's native format. See [Model strategy](#model-strategy) below.
- **Use whatever hardware the device has** — NPU first, then dGPU, then iGPU, then CPU. Pick the *best* for the current job when multiple are available.
- **Refuse, don't downsize** — if the device can't fit Gemma 4 E4B (mobile philosophy), the device isn't supported. We do not silently swap in a smaller model.
- **Lean install** — installer is ~30 MB. Heavy bits (runtime libs + model) download only after we know what the device needs.
- **Two new features over mobile** — deep research mode + native artifacts.

## Model strategy

**One model, three native formats, multiple quantization tiers.**

| Platform | Format | Source command |
|---|---|---|
| Windows | ONNX (KV-cache, GenAI extension) | `optimum-cli export onnx --model google/gemma-3n-E4B-it ...` or use Microsoft's ONNX GenAI model builder |
| macOS   | MLX | `mlx_lm.convert --hf-path google/gemma-3n-E4B-it -q` |
| Linux   | GGUF (llama.cpp) and OpenVINO IR (Intel NPU) | Use existing community quants (e.g. `bartowski/gemma-3n-E4B-it-GGUF`) for llama.cpp; convert to OpenVINO IR for the NPU path |

Mobile (`.litertlm`) is **not** reused on desktop — LiteRT-LM is mobile-focused and lacks the NPU/GPU runtime coverage we need across three OSes. The weights are the same Gemma 3n E4B; the on-disk container changes per platform so each native runtime can load them efficiently.

**Quantization is the only variable per hardware tier:**
- fp16 on dGPU with ≥10 GB VRAM
- int8 on dGPU with 6–10 GB VRAM, or iGPU with ≥12 GB unified memory
- int4 (Q4_K_M for GGUF) on smaller iGPU or CPU
- NPU formats use whatever quantization the NPU runtime requires (typically int8 or int4)

**Minimum hardware** (below this we refuse to install, like mobile refuses on incompatible phones):
- 16 GB system RAM
- 4 GB GPU VRAM **or** 12 GB shared iGPU memory **or** a supported NPU
- ~5 GB free disk for the model + runtime

## Hardware selection (runtime)

Every launch, the app runs a hardware probe and asks the manifest server for the right artifacts.

```
   Launch
     │
     ▼
┌──────────────────┐
│ Hardware probe   │   (native to each OS — see per-platform Hardware/ folders)
│ - CPU SKU, cores │
│ - RAM            │
│ - GPU vendor/VRAM│
│ - NPU available? │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────┐
│ Backend selector                 │
│                                  │
│ Priority order:                  │
│   1. NPU (if available + model   │
│      fits)                       │
│   2. dGPU (≥6 GB VRAM)           │
│   3. iGPU (shared ≥8 GB usable)  │
│   4. CPU (≥16 GB RAM, small      │
│      model variant)              │
│                                  │
│ Per-job override:                │
│   - short prompts → prefer NPU   │
│     (lower latency, lower power) │
│   - long context → prefer GPU    │
│     (higher throughput)          │
└────────┬─────────────────────────┘
         │
         ▼
┌──────────────────────────────────┐
│ Manifest fetch                   │
│ GET /manifest.json               │
│                                  │
│ Server returns matching:         │
│  - runtime URL + sha256          │
│  - model URL + sha256            │
│  - any backend-specific libs     │
└────────┬─────────────────────────┘
         │
         ▼
┌──────────────────────────────────┐
│ Resumable download               │
│ (only what's missing locally)    │
└──────────────────────────────────┘
```

## Manifest format

The manifest is a single JSON file served from the Localyze CDN. The schema lives at [shared/manifest.schema.json](shared/manifest.schema.json). Conceptually:

```json
{
  "version": "1.0.0",
  "tiers": [
    {
      "id": "win-npu-snapdragon",
      "match": { "os": "windows", "npu": "qualcomm-hexagon", "ram_gb": ">=16" },
      "runtime": { "url": "...onnxruntime-qnn.zip", "sha256": "..." },
      "model": { "url": "...gemma4-e4b-qnn.onnx", "sha256": "...", "size_mb": 2100 }
    },
    {
      "id": "mac-apple-silicon",
      "match": { "os": "macos", "chip": "apple-silicon", "ram_gb": ">=16" },
      "runtime": { "url": null, "sha256": null },  // MLX is bundled with the app
      "model": { "url": "...gemma4-e4b-mlx.tar", "sha256": "...", "size_mb": 2400 }
    },
    {
      "id": "linux-vulkan-generic",
      "match": { "os": "linux", "gpu_api": "vulkan", "vram_gb": ">=4" },
      "runtime": { "url": "...llamacpp-vulkan-linux.tar.gz", "sha256": "..." },
      "model": { "url": "...gemma4-e4b-q4.gguf", "sha256": "...", "size_mb": 2200 }
    }
  ]
}
```

The selector walks `tiers[]` in priority order, picks the first matching tier, and the downloader fetches whatever isn't already on disk.

## Deep research mode

ReAct loop. Same conceptual flow on all three platforms (implementations are per-language, see each platform's `Research/` folder):

```
user prompt
     │
     ▼
[Plan]  ── model writes a plan: subgoals + tools to call
     │
     ▼
loop:
  [Reason]  ── model emits next action: tool call or final answer
  [Act]     ── execute tool (local memory / file RAG / calc / code / web*)
  [Observe] ── tool result fed back into context
  break when model emits <final>
     │
     ▼
[Synthesize] ── final answer with citations to sources
```

**Web search** is a single binary toggle in Settings. OFF → web tool is unavailable to the agent. ON → freely callable. No "auto", no per-query prompts. Default OFF (privacy-first).

Tool roster (always available regardless of web toggle):
- `memory.search(query)` — searches local conversation memory + saved notes
- `files.search(query)` — searches attached files via local embeddings
- `calc(expr)` — calculator
- `run(lang, code)` — local code execution (Python via Pyodide, JS via Node)
- `system.info()` — OS/CPU/RAM/GPU info

Web-gated tool:
- `web.search(query)` — only when `web_search_enabled = true`

## Artifacts (richer than Claude's)

The model emits typed blocks inline. The native UI parses them and renders with platform widgets — no iframes, no JS sandbox.

Block format (shared across platforms — see [shared/viz-schema.md](shared/viz-schema.md)):

```
<viz type="chart" kind="bar" title="Revenue by quarter" x="quarter" y="revenue" data='[...]' />
<viz type="table" data='[...]' editable="true" />
<viz type="map" markers='[...]' />
<viz type="run" lang="python" code="..." />
<viz type="code" lang="rust">...</viz>
<viz type="form" schema='{...}' />
<viz type="image" src="..." />
```

Each platform's `Artifacts/` folder has a parser + renderer per type using native widgets.

Capabilities that beat Claude's iframe-bound artifacts:
- **Editable tables** save back to CSV/XLSX with one click.
- **Real code execution** — outputs feed straight into the next viz block.
- **Live streaming** — chart/table updates token-by-token as the model writes data.
- **Auto-save** — every artifact is written to `~/Localyze/artifacts/` and indexed for later memory recall.
- **Native export menu** — PDF, PNG, CSV, XLSX, OS share sheet, on every block.

## Cross-platform protocol (the only shared thing)

These three things are identical strings across platforms:
1. The `<viz ...>` block format (so a saved conversation renders the same in any client).
2. The manifest JSON schema (so the server only ships one manifest).
3. The set of tool names and signatures (so the prompt template is shared).

Everything else — UI, inference glue, OS plumbing — is platform-specific.
