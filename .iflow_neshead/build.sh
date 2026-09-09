#!/bin/bash
# Build headless fceux test driver — .c with clang (C), .cpp with clang++ (C++).
set -e
cd "$(dirname "$0")/.."
ROOT="$PWD"
OBJ="$ROOT/.iflow_neshead/obj"
rm -rf "$OBJ"
mkdir -p "$OBJ"

DEFS="-DPSS_STYLE=1 -DHAVE_ASPRINTF -DLSB_FIRST -DFRAMESKIP -DIOAPI_NO_64 -D_GLIBCXX_USE_WCHAR_T -DNDEBUG"
INCS="-I$ROOT/core/fceux -I$ROOT/core/fceux/boards -I$ROOT/core/fceux/input"

compile_one() {
  local f="$1"
  local o="$OBJ/$(echo "$f" | md5sum | cut -c1-12).o"
  case "$f" in
    *.c)   clang  -c -std=c11     -O1 -w $DEFS $INCS "$f" -o "$o" ;;
    *.cpp) clang++ -c -std=gnu++11 -O1 -w $DEFS $INCS "$f" -o "$o" ;;
  esac
}
export -f compile_one
export OBJ DEFS INCS ROOT

FILES=$(find core/fceux -name "*.c" -o -name "*.cpp" | sort)
echo "$FILES" | xargs -P 8 -I{} bash -c 'compile_one "$@"' _ {}

# driver
clang++ -c -std=gnu++11 -O1 -w $DEFS $INCS "$ROOT/.iflow_neshead/neshead.cpp" -o "$OBJ/neshead.o"

clang++ "$OBJ"/*.o -lz -o "$ROOT/.iflow_neshead/neshead"
echo "BUILD OK: $ROOT/.iflow_neshead/neshead"