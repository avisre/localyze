cmake_minimum_required(VERSION 3.21)
project(Localyze LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

find_package(Qt6 6.7 REQUIRED COMPONENTS Quick QuickControls2 Charts Network Widgets)
qt_standard_project_setup()

# ---------------------------------------------------------------------------
# llama.cpp dependency
#
# We vendor llama.cpp as a git submodule under third_party/llama.cpp.
# Backend variant (CUDA / HIP / Vulkan / CPU) is selected at build time
# via the LOCALYZE_BACKEND variable. Default: Vulkan (works everywhere).
# ---------------------------------------------------------------------------
set(LOCALYZE_BACKEND "vulkan" CACHE STRING "llama.cpp backend: cpu | vulkan | cuda | hip")

if(LOCALYZE_BACKEND STREQUAL "vulkan")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
elseif(LOCALYZE_BACKEND STREQUAL "cuda")
    set(GGML_CUDA   ON CACHE BOOL "" FORCE)
elseif(LOCALYZE_BACKEND STREQUAL "hip")
    set(GGML_HIP    ON CACHE BOOL "" FORCE)
elseif(LOCALYZE_BACKEND STREQUAL "cpu")
    # default GGML CPU build
else()
    message(FATAL_ERROR "Unknown LOCALYZE_BACKEND=${LOCALYZE_BACKEND}")
endif()

add_subdirectory(third_party/llama.cpp EXCLUDE_FROM_ALL)

qt_add_executable(Localyze
    src/main.cpp
    src/hardware/HardwareProbe.cpp
    src/hardware/HardwareProbe.h
    src/inference/BackendSelector.cpp
    src/inference/BackendSelector.h
    src/inference/LlamaCppBackend.cpp
    src/inference/LlamaCppBackend.h
    src/inference/ModeStore.cpp
    src/inference/ModeStore.h
    src/inference/StyleStore.cpp
    src/inference/StyleStore.h
    src/inference/SystemPromptBuilder.cpp
    src/inference/SystemPromptBuilder.h
    src/inference/PromptPreFilter.cpp
    src/inference/PromptPreFilter.h
    src/download/ModelDownloader.cpp
    src/download/ModelDownloader.h
    src/research/ReActAgent.cpp
    src/research/ReActAgent.h
    src/research/Tools.cpp
    src/research/Tools.h
    src/research/TestSpectator.cpp
    src/research/TestSpectator.h
    src/research/PromptFeeder.cpp
    src/research/PromptFeeder.h
    src/artifacts/VizParser.cpp
    src/artifacts/VizParser.h
    src/settings/SettingsStore.cpp
    src/settings/SettingsStore.h
    src/settings/ThemeBridge.cpp
    src/settings/ThemeBridge.h
    src/conversation/ConversationStore.cpp
    src/conversation/ConversationStore.h
)

# Theme.qml is a singleton — mark its source property so qt_add_qml_module
# generates a qmldir entry of `singleton Theme 1.0 Theme.qml`.
set_source_files_properties(qml/Theme.qml PROPERTIES
    QT_QML_SINGLETON_TYPE TRUE
)

qt_add_qml_module(Localyze
    URI LocalyzeUI
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/ChatView.qml
        qml/ArtifactView.qml
        qml/TestSpectatorView.qml
        qml/Theme.qml
        qml/MessageBody.qml
        qml/ConversationDrawer.qml
        qml/ModePicker.qml
        qml/StylePicker.qml
        qml/OnboardingView.qml
        qml/ThoughtCard.qml
        qml/ArtifactPanel.qml
        qml/FollowUpChips.qml
        qml/SlashMenu.qml
        qml/WritingToolsPopover.qml
)

target_link_libraries(Localyze PRIVATE
    Qt6::Quick
    Qt6::QuickControls2
    Qt6::Charts
    Qt6::Network
    Qt6::Widgets
    llama
)
target_include_directories(Localyze PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/include
)
