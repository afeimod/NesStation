#pragma once

#include <cstdint>
#include <filesystem>
#include <string>

// Runtime savestate support for the Android port.
//
// Unlike the quick-save slots (which only copy the MLC save data directory),
// this module captures the full emulation state of the running title:
//  - the complete mapped emulated memory (which contains every OSThread's
//    register context, since the scheduler stores contexts in emulated memory)
//  - host-side state that is required to make the snapshot consistent
//    (SysAllocator bump offset, open file handle positions and writable file
//    contents)
//
// Restoring happens fully in-place: the title is paused, the memory is
// overwritten with the snapshot, the scheduler is rebuilt from the restored
// thread queue and the title resumes exactly where it was when the snapshot
// was taken. The game is never shut down or restarted.
namespace CafeSaveState
{
	struct OperationResult
	{
		bool success{ false };
		std::string errorMessage;
	};

	// Freezes the emulated title so that its state can be captured or restored.
	// Freeze = pause all PPC threads + wait until every thread stored its
	// register context + stop the virtual vsync timing and OS system events +
	// wait for the GPU to retire all submitted commands.
	// If the title was already paused by the user it stays paused after end().
	class EmulationFreeze
	{
	public:
		EmulationFreeze() = default;
		~EmulationFreeze();

		EmulationFreeze(const EmulationFreeze&) = delete;
		EmulationFreeze& operator=(const EmulationFreeze&) = delete;

		// Returns false (with a reason in errorOut) if a stable freeze could not
		// be reached within the timeout. In that case emulation is unmodified.
		bool begin(std::string& errorOut, bool gpuIdleRequired = true);

		// Unfreezes emulation. Safe to call multiple times.
		void end();

		// Savestate support: after restoring, drop all cached GPU data (textures,
		// vertex/index buffers) so everything is re-uploaded from the restored
		// memory. Must be called before end().
		void RequestGpuCacheReset();

	private:
		bool m_active{ false };
		bool m_frozenTiming{ false };
		bool m_pausedByUs{ false };
		bool m_wasPaused{ false };
		bool m_resetGpuCaches{ false };
		// v3.9: true while this instance owns the process-wide freeze slot
		// (released in end(), which runs from the destructor even when begin()
		// failed midway)
		bool m_slotHeld{ false };
	};

	// Captures the current emulation state into a single file (written via a
	// temporary file and renamed into place). Pauses the title for the
	// duration of the capture and restores the previous pause state afterwards.
	OperationResult CaptureStateToFile(uint64 titleId, const std::filesystem::path& filePath);

	// Restores the emulation state from a file created by CaptureStateToFile.
	// The title must be running and must be the same title the state was
	// captured from. The restore is applied in-place, the game continues
	// without being restarted.
	// The file is validated completely before any emulated memory is modified,
	// so a failed restore never leaves the emulation in a corrupted state.
	OperationResult RestoreStateFromFile(uint64 expectedTitleId, const std::filesystem::path& filePath);

	// Low-level variants for callers that already hold an active EmulationFreeze.
	// Must not be called without an active freeze.
	OperationResult CaptureStateToFileLocked(uint64 titleId, const std::filesystem::path& filePath);
	OperationResult RestoreStateFromFileLocked(uint64 expectedTitleId, const std::filesystem::path& filePath);
}
