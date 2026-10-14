#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

// Cheat code (RAM patch) support for the Android port.
//
// Loads cheat .txt files in the community Cemu cheat format from
// <userDataPath>/cheatCodes/ and applies them to the emulated memory of the
// running title:
//
//   [Cheat name]
//   * comment line (optional, shown as description)
//   034B7E40 3C608080      <- 32-bit virtual address + 32-bit value (hex)
//
// A file applies to a title when its file name contains the title's
// 16-digit title id (e.g. 0005000010143500). All matching files are merged.
//
// Enabled patches are re-applied after a savestate restore (the snapshot
// overwrites the patched memory) and un-applied (original values restored)
// when a cheat is disabled again.
namespace Cheats
{
	struct CheatPatch
	{
		uint32 address{ 0 };
		uint32 value{ 0 };
	};

	struct CheatEntry
	{
		std::string name;
		std::string comment;
		bool enabled{ false };
		bool applied{ false };
		std::vector<CheatPatch> patches;
		std::vector<uint32> originalValues;
	};

	class CheatManager
	{
	public:
		static CheatManager& GetInstance();

		// (Re)loads all cheat files matching the given title id. Returns the
		// number of parsed cheat entries. Disables and clears all previously
		// loaded cheats first (original values are not restored - callers use
		// this either before a title runs or together with UnapplyAll).
		size_t LoadCheatsForTitle(uint64 titleId);
		void UnloadCheats();

		size_t GetCheatCount() const;
		std::string GetCheatName(size_t index) const;
		std::string GetCheatComment(size_t index) const;
		bool IsCheatEnabled(size_t index) const;

		void SetCheatEnabled(size_t index, bool enabled);

		// Re-applies every enabled cheat. Called after an in-place savestate
		// restore overwrote the patched emulated memory.
		void ReapplyAll();

	private:
		CheatManager() = default;

		bool ApplyCheat(CheatEntry& entry);
		bool UnapplyCheat(CheatEntry& entry);
		bool WritePatchValue(CheatEntry& entry, size_t patchIndex, uint32 value, bool rememberOriginal);

		mutable std::mutex m_mutex;
		std::vector<CheatEntry> m_cheats;
	};
}
