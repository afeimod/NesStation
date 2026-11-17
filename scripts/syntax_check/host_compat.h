// Host tests build Android code on Windows without changing production APIs.
#pragma once
#ifdef _WIN32
#include <direct.h>
#define mkdir(path, mode) _mkdir(path)
#endif
