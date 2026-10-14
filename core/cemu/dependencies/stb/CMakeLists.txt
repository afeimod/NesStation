# [NesStation 集成补丁] 3.25 → 3.22.1（主工程 AGP 8.1.0 自带 CMake 3.22.1）。
# stb 是纯 INTERFACE 头文件库，不使用任何 3.23+ 特性。
cmake_minimum_required(VERSION 3.22.1)

project(stb LANGUAGES C)

add_library(stb INTERFACE)

target_include_directories(stb INTERFACE include)
