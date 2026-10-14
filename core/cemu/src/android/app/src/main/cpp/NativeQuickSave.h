#pragma once

#include <jni.h>

// Quick save / quick load (full emulation state snapshot slots with screenshot
// thumbnails; a copy of the MLC save data is kept as fallback for restores
// after the emulator process was closed)
// for the Android port. All functions operate on the currently running title.
namespace NativeQuickSave
{
	// Result codes shared between C++ and Kotlin
	enum QuickSaveResult : jint
	{
		QUICKSAVE_OK = 0,
		QUICKSAVE_ERROR_NOT_RUNNING = 1,   // no title is currently running
		QUICKSAVE_ERROR_NO_SAVE_DATA = 2,  // the game has not created any save data yet
		QUICKSAVE_ERROR_SLOT_EMPTY = 3,    // restore target slot does not exist
		QUICKSAVE_ERROR_IO = 4,            // filesystem error while copying
		QUICKSAVE_OK_RESTORED_IN_PLACE = 5, // state restored in-place, no relaunch needed
		QUICKSAVE_ERROR_STATE_RESTORE_FAILED = 6, // state restore failed, title still running
	};

	// flags for quickRestoreSlot
	constexpr jint QUICKRESTORE_FLAG_ALLOW_LEGACY = 1; // on in-place failure: shut the title down, restore the slot save data and let the caller relaunch
}
