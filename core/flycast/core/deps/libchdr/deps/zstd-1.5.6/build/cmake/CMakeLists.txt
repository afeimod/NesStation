cmake_minimum_required(VERSION 3.10)

# We default to a static library, but the user can change this
option(ZSTD_BUILD_SHARED "BUILD SHARED LIBRARY" OFF)

# Make sure our local CMake Modules path comes first
list(INSERT CMAKE_MODULE_PATH 0 ${CMAKE_CURRENT_SOURCE_DIR}/cmake/Modules)

# Set the default build type to Release
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

project(zstd LANGUAGES C)

set(ZSTD_SRCS
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/debug.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/entropy_common.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/error_private.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/fse_decompress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/pool.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/threading.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/xxhash.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common/zstd_common.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/fse_compress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/hist.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/huf_compress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_compress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_compress_literals.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_compress_sequences.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_compress_superblock.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_double_fast.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_fast.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_lazy.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_ldm.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstd_opt.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress/zstdmt_compress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/decompress/huf_decompress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/decompress/zstd_ddict.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/decompress/zstd_decompress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/decompress/zstd_decompress_block.c
)

set(ZSTD_INCLUDES
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/common
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/compress
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/decompress
)

if(ZSTD_LEGACY_SUPPORT)
  set(ZSTD_SRCS ${ZSTD_SRCS}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/decompress/zstd_decompress.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/legacy/zstd_v01.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/legacy/zstd_v02.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/legacy/zstd_v03.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/legacy/zstd_v04.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/legacy/zstd_v05.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/legacy/zstd_v06.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../../lib/legacy/zstd_v07.c
  )
  add_definitions(-DZSTD_LEGACY_SUPPORT=1)
endif()

if(ZSTD_BUILD_SHARED)
  add_library(libzstd_shared SHARED ${ZSTD_SRCS})
  target_include_directories(libzstd_shared PUBLIC ${ZSTD_INCLUDES})
  set_target_properties(libzstd_shared PROPERTIES
    SOVERSION 1
    VERSION 1.5.6
  )
  install(TARGETS libzstd_shared
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
  )
endif()

add_library(libzstd_static STATIC ${ZSTD_SRCS})
target_include_directories(libzstd_static PUBLIC ${ZSTD_INCLUDES})
set_target_properties(libzstd_static PROPERTIES
  OUTPUT_NAME zstd
  POSITION_INDEPENDENT_CODE ON
)
install(TARGETS libzstd_static
  ARCHIVE DESTINATION lib
)
