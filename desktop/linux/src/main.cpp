#include <QApplication>
#include <QFileInfo>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QTimer>

#include "download/ModelDownloader.h"
#include "hardware/HardwareProbe.h"
#include "inference/BackendSelector.h"
#include "inference/LlamaCppBackend.h"
#include "inference/ModeStore.h"
#include "inference/StyleStore.h"
#include "research/PromptFeeder.h"
#include "research/ReActAgent.h"
#include "research/TestSpectator.h"
#include "settings/SettingsStore.h"
#include "settings/ThemeBridge.h"
#include "conversation/ConversationStore.h"

namespace {
int gpuLayersFor(localyze::BackendKind k) {
    using K = localyze::BackendKind;
    switch (k) {
        case K::LlamaCppCpu:    return 0;
        case K::LlamaCppCuda:
        case K::LlamaCppRocm:
        case K::LlamaCppVulkan: return 999;   // offload all layers
        case K::OpenVinoNpu:    return 0;     // OpenVINO path, separate backend
    }
    return 0;
}
}

int main(int argc, char* argv[]) {
    // NOTE: must be QApplication (Widgets module), not QGuiApplication. QtCharts
    // pulls in QWidgetTextControl for its chart titles, and that type requires
    // a Widgets-capable QApplication. With QGuiApplication, the first time a
    // MessageBody delegate instantiates a Chart{} (which happens on the first
    // assistant message being appended to the ListModel), the QGraphicsTextItem
    // inside QChart dereferences a null d-pointer in QWidgetTextControl and
    // SIGSEGVs the whole process. Took 3 agents and a gdb backtrace to find.
    QApplication app(argc, argv);
    app.setApplicationName("Localyze");
    app.setOrganizationName("Localyze");

    auto hw = localyze::HardwareProbe::run();
    // Test/debug override: LOCALYZE_FORCE_BACKEND=cpu masks GPU + NPU so the
    // selector falls all the way through to the CPU tier. Use only for
    // quality testing on CPU; do not ship as a user-facing knob.
    if (qEnvironmentVariable("LOCALYZE_FORCE_BACKEND").compare("cpu", Qt::CaseInsensitive) == 0) {
        hw.gpuApi = localyze::GpuApi::None;
        hw.vramGB = 0;
        hw.gpuName = "(masked by LOCALYZE_FORCE_BACKEND=cpu)";
        hw.npu = localyze::NpuKind::None;
    }

    // Hardware-aware context length. Saved to SettingsStore so the QML
    // composer can show "Auto (16384)" + a user override.
    const int recommended = localyze::LlamaCppBackend::recommendedContext(hw.vramGB, hw.ramGB);
    localyze::SettingsStore::instance().setRecommendedContextSize(recommended);
    const int effective = localyze::SettingsStore::instance().effectiveContextSize();

    QString backendLabel, backendReason;
    localyze::LlamaCppBackend* backend = nullptr;
    try {
        const auto selection = localyze::BackendSelector::pick(hw);
        backendLabel  = QString("Gemma 4 E4B (%1) — %2 · ctx %3")
                            .arg(QString::fromStdString(selection.quantization),
                                 QString::fromStdString(selection.label))
                            .arg(effective);
        backendReason = QString::fromStdString(selection.reason);

        const auto modelPath = localyze::SettingsStore::instance().modelPath();
        if (QFileInfo::exists(modelPath)) {
            backend = new localyze::LlamaCppBackend(selection.kind, modelPath);
            // Keep backend on the main thread permanently. Moving it to a
            // worker thread (even deferred until after QML loads its
            // `Connections { target: backend }`) races with QML's connection
            // marshalling and crashes (SIGSEGV) the first time the user
            // triggers backend.generate() from QML. The simpler, crash-free
            // contract: backend lives on the main thread, model load blocks
            // startup for ~2 s, and each chat turn blocks the UI during
            // decode. decodePrompt() already uses llama_synchronize() and an
            // atomic generating_ guard so it is safe on whichever thread
            // owns the QObject — which is now the GUI thread.
            auto err = backend->load(gpuLayersFor(selection.kind), effective);
            if (!err.isEmpty()) qWarning() << "load:" << err;
        } else {
            backendReason += QString("  •  model not at %1").arg(modelPath);
        }
    } catch (const localyze::UnsupportedHardware& e) {
        backendLabel  = QStringLiteral("Unsupported hardware");
        backendReason = QString::fromUtf8(e.what());
    }

    auto* agent = new localyze::ReActAgent(&app);
    agent->setBackend(backend);

    auto* spectator = new localyze::TestSpectator(&app);
    auto* feeder    = new localyze::PromptFeeder(&app);
    auto* themeBridge = new localyze::ThemeBridge(&app);
    auto* modelDownloader = new localyze::ModelDownloader(&app);

    // When ReActAgent finishes a turn, let the feeder advance to the next.
    QObject::connect(agent, &localyze::ReActAgent::finished, feeder, &localyze::PromptFeeder::markDone);
    // The direct (backend.generate()) route also needs to release the feeder.
    // Without this, after a simple prompt routes through backend instead of
    // the agent, ReActAgent::finished never fires and the feeder stays busy_
    // forever, blocking all subsequent injects.
    QObject::connect(backend, &localyze::LlamaCppBackend::finished, feeder, &localyze::PromptFeeder::markDone);

    // ---- Auto-test mode: if /tmp/qrepo/inject.txt already has content and
    // the file /tmp/qrepo/AUTO_RUN exists at startup, skip onboarding and
    // begin the prompt-feeder loop immediately so the test fleet runs hands-
    // free. Touch /tmp/qrepo/AUTO_RUN before launching to enable this.
    if (QFileInfo::exists("/tmp/qrepo/AUTO_RUN")) {
        localyze::SettingsStore::instance().setOnboarded(true);
        QTimer::singleShot(2500, feeder, &localyze::PromptFeeder::start);
    }

    QQmlApplicationEngine engine;
    // Note: context properties cannot share names with QML target properties or
    // they get shadowed by the scope chain. Prefix everything with `ctx_` to avoid it.
    engine.rootContext()->setContextProperty("ctxBackendLabel", backendLabel);
    engine.rootContext()->setContextProperty("ctxBackendReason", backendReason);
    engine.rootContext()->setContextProperty("researchAgent", agent);
    // === ROUTING ADDITION START ===
    // Expose the LlamaCppBackend so ChatView can route simple prompts directly
    // to backend.generate() and skip the ReAct multi-step machinery.
    engine.rootContext()->setContextProperty("backend", backend);
    // === ROUTING ADDITION END ===
    engine.rootContext()->setContextProperty("settings", &localyze::SettingsStore::instance());
    engine.rootContext()->setContextProperty("modeStore", &localyze::ModeStore::instance());
    engine.rootContext()->setContextProperty("styleStore", &localyze::StyleStore::instance());
    engine.rootContext()->setContextProperty("testSpectator", spectator);
    engine.rootContext()->setContextProperty("promptFeeder", feeder);
    engine.rootContext()->setContextProperty("themeBridge", themeBridge);
    engine.rootContext()->setContextProperty("modelDownloader", modelDownloader);
    engine.rootContext()->setContextProperty("conversationStore",
                                             &localyze::ConversationStore::instance());

    engine.loadFromModule("LocalyzeUI", "Main");
    if (engine.rootObjects().isEmpty()) return -1;
    return app.exec();
}
