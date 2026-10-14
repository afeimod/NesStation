#pragma once
#include <vector>
#include <utility>
#include <cstdint>
#include <cstddef>

// Lightweight in-process function registry used by the savestate crash reporter.
//
// Each translation unit registers its scheduler/interpreter/HLE entry points via
// SAVESTATE_REGISTER_FN(). When a fatal signal occurs, the crash handler walks
// this table and reports the registered function nearest below each backtrace
// PC ("lib+0x1234 in coreinit::__OSThreadSwitchToNext+0x1c"). This works even
// for stripped release builds where no .symtab is available, and it removes the
// need to manually correlate raw module offsets between runs.
//
// The table is filled by static initializers long before any emulation runs,
// and is only read from the crash handler, so no runtime locking is required.

namespace savestate_fsym
{
        inline std::vector<std::pair<const void*, const char*>>& GetTable()
        {
                static std::vector<std::pair<const void*, const char*>> s_table;
                return s_table;
        }

        inline void Add(const void* fn, const char* name)
        {
                GetTable().emplace_back(fn, name);
        }

        // find the registered function whose entry address is the highest one not
        // above 'pc'. 'pc' and all table entries are compared in module-relative
        // space: table entries store absolute addresses, moduleBase is subtracted
        // internally (entries below moduleBase are skipped). deltaOut receives
        // pc - functionStart
        inline const char* FindNearest(std::uint64_t pcRel, std::uint64_t moduleBase, std::uint64_t& deltaOut)
        {
                const char* bestName = nullptr;
                std::uint64_t bestStart = 0;
                for (const auto& entry : GetTable())
                {
                        const std::uint64_t absolute = (std::uint64_t)(uintptr_t)entry.first;
                        if (moduleBase != 0 && absolute >= moduleBase)
                        {
                                const std::uint64_t start = absolute - moduleBase;
                                if (start > pcRel)
                                        continue;
                                if (!bestName || start > bestStart)
                                {
                                        bestName = entry.second;
                                        bestStart = start;
                                }
                        }
                        else if (moduleBase == 0)
                        {
                                // no base known - fall back to comparing raw addresses
                                if (absolute > pcRel)
                                        continue;
                                if (!bestName || absolute > bestStart)
                                {
                                        bestName = entry.second;
                                        bestStart = absolute;
                                }
                        }
                }
                if (bestName)
                        deltaOut = pcRel - bestStart;
                return bestName;
        }
}

#define SAVESTATE_FSYM_CONCAT_IMPL(a, b) a##b
#define SAVESTATE_FSYM_CONCAT(a, b) SAVESTATE_FSYM_CONCAT_IMPL(a, b)

// register one function with the savestate crash reporter. Place at file scope
// (inside the same namespace as the function, or fully qualify the name).
// Only works for unambiguous, non-overloaded functions.
#define SAVESTATE_REGISTER_FN(fn) \
        namespace { \
                [[maybe_unused]] const bool SAVESTATE_FSYM_CONCAT(s_saveStateFnSymReg_, __COUNTER__) = \
                        (savestate_fsym::Add((const void*)(&fn), #fn), true); \
        }
