cmake_minimum_required(VERSION 3.22.1)
project("rhythmic-touch-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── Dobby inline hook framework ────────────────────────────────
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
set(Plugin.ImportTableReplace OFF CACHE BOOL "" FORCE)
set(Plugin.Android.BionicLinkerUtil OFF CACHE BOOL "" FORCE)
set(FullFloatingPointRegisterPack OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    dobby
    GIT_REPOSITORY https://github.com/jmpews/Dobby.git
    GIT_TAG 5f9762afb9866e169ec4d127dd5361b9998cdfa8
)
FetchContent_MakeAvailable(dobby)

find_library(log-lib log)

# ─── OboePcmCapture library ─────────────────────────────────────
add_library(oboe-pcm-capture SHARED
    phira/OboePcmCapture.cpp
)
target_include_directories(oboe-pcm-capture PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/phira)
target_link_libraries(oboe-pcm-capture dobby ${log-lib} dl android)

# ─── RhythmicHook: loaded into audioserver ──────────────────────
add_library(rhythmic-hook SHARED
    hook/rhythmic_hook.c
)
target_link_libraries(rhythmic-hook dobby ${log-lib} dl android)

# ─── RhythmicDaemon: native executable (root, ptrace + dumpsys) ──
add_executable(rhythmic-daemon
    daemon/rhythmic_daemon.c
)
set_target_properties(rhythmic-daemon PROPERTIES
    OUTPUT_NAME "rhythmic_daemon"
    PREFIX ""
)
target_link_libraries(rhythmic-daemon ${log-lib} dl)
