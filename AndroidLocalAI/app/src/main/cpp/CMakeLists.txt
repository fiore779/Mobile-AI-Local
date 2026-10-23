cmake_minimum_required(VERSION 3.22.1)
# デバッグビルドでも推論が遅くならないよう、ネイティブ部分は常に Release にする
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
project("mal_llama" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp(b11796 に固定)。ライブラリだけをビルドし、サーバーやサンプルは作らない。
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(mal_llama SHARED mal_llama.cpp)
target_include_directories(mal_llama PRIVATE llama.cpp/include llama.cpp/ggml/include)
target_link_libraries(mal_llama llama android log)
