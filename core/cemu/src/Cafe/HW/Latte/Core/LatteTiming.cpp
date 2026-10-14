#include "Cafe/HW/Latte/Core/Latte.h"
#include "Cafe/OS/libs/gx2/GX2_Event.h"
#ifdef ENABLE_VULKAN
#include "Cafe/HW/Latte/Renderer/Vulkan/VsyncDriver.h"
#endif
#include "util/highresolutiontimer/HighResolutionTimer.h"
#include <atomic>
#include "config/CemuConfig.h"
#include "Cafe/CafeSystem.h"

sint32 s_customVsyncFrequency = -1;

// emulation speed multiplier in percent (100 = normal speed, 200 = 2x fast forward, 50 = 0.5x slow motion)
// scales the simulated vsync frequency so that flip-locked game loops (GX2WaitSwap /
// GX2SwapScanBuffers) speed up alongside the emulated timer shift
static std::atomic<sint32> s_speedMultiplierPercent{ 100 };

void LatteTiming_setSpeedMultiplier(sint32 multiplierPercent)
{
	if (multiplierPercent < 10)
		multiplierPercent = 10;
	s_speedMultiplierPercent = multiplierPercent;
}

// freeze/resume the virtual vsync timing. Used by the savestate system while
// a snapshot is captured or restored so that no GPU thread writes into
// emulated memory and no vsync events pile up during the pause
static std::atomic<bool> s_timingPaused{ false };

// forward declaration - defined below; used by LatteTiming_setPaused to
// reset the vsync timer right after resuming
HRTick LatteTime_CalculateTimeBetweenVSync();

void LatteTiming_setPaused(bool paused)
{
	if (s_timingPaused == paused)
		return;
	s_timingPaused = paused;
	if (!paused)
	{
		// avoid a burst of catch-up vsync events right after resuming
		LatteGPUState.timer_nextVSync = HighResolutionTimer::now().getTick() + LatteTime_CalculateTimeBetweenVSync();
	}
}

void LatteTiming_NotifyHostVSync();

// calculate time between vsync events in timer units
// standard rate on Wii U is 59.94, however to prevent tearing and microstutter on ~60Hz displays it is better if we slightly overshoot 60 Hz
// can be modified by graphic packs
HRTick LatteTime_CalculateTimeBetweenVSync()
{
	// 59.94 -> 60 * 0.999

	HRTick tick = HighResolutionTimer::getFrequency();
	if (s_customVsyncFrequency > 0)
	{
		tick /= (uint64)s_customVsyncFrequency;
	}
	else
	{
		tick *= 1000ull;
		tick /= 1002ull;
		tick /= 60ull;
	}
	const sint32 speedMultiplier = s_speedMultiplierPercent.load(std::memory_order_relaxed);
	if (speedMultiplier != 100)
	{
		tick *= 100ull;
		tick /= (uint64)speedMultiplier;
	}
	return tick;
}

void LatteTiming_setCustomVsyncFrequency(sint32 frequency)
{
	s_customVsyncFrequency = frequency;
}

void LatteTiming_disableCustomVsyncFrequency()
{
	s_customVsyncFrequency = -1;
}

bool LatteTiming_getCustomVsyncFrequency(sint32& customFrequency)
{
	sint32 t = s_customVsyncFrequency;
	if (t <= 0)
		return false;
	customFrequency = t;
	return true;
}

bool s_usingHostDrivenVSync = false;

void LatteTiming_EnableHostDrivenVSync()
{
	if (s_usingHostDrivenVSync)
		return;
	#ifdef ENABLE_VULKAN
	VsyncDriver_startThread(LatteTiming_NotifyHostVSync);
	s_usingHostDrivenVSync = true;
	#endif
}

bool LatteTiming_IsUsingHostDrivenVSync()
{
	return s_usingHostDrivenVSync;
}

void LatteTiming_Init()
{
	LatteGPUState.timer_frequency = HighResolutionTimer::getFrequency();
	LatteGPUState.timer_bootUp = HighResolutionTimer::now().getTick();
	LatteGPUState.timer_nextVSync = LatteGPUState.timer_bootUp + LatteTime_CalculateTimeBetweenVSync();
}

void LatteTiming_signalVsync()
{
	static uint32 s_vsyncIntervalCounter = 0;

	if (!LatteGPUState.gx2InitCalled)
		return;
	s_vsyncIntervalCounter++;
	uint32 swapInterval = 1;
	if (LatteGPUState.sharedArea)
		swapInterval = LatteGPUState.sharedArea->swapInterval;

	// flip
	if (s_vsyncIntervalCounter >= swapInterval)
	{
		if (LatteGPUState.sharedArea)
		{
			// hack/workaround - only execute flip if GX2SwapScanBuffers() isn't lagging behind
			uint64 currentTitleId = CafeSystem::GetForegroundTitleId();
			if (currentTitleId == 0x00050000101c9500 || currentTitleId == 0x00050000101c9400 || currentTitleId == 0x0005000e101c9300)
			{
				uint32 currentFlipRequestCount = _swapEndianU32(LatteGPUState.sharedArea->flipRequestCountBE);
				uint32 currentFlipExecuteCount = _swapEndianU32(LatteGPUState.sharedArea->flipExecuteCountBE);

				if ((currentFlipRequestCount >= currentFlipExecuteCount) || (currentFlipExecuteCount - currentFlipRequestCount < 4))
				{
					LatteGPUState.sharedArea->flipExecuteCountBE = _swapEndianU32(_swapEndianU32(LatteGPUState.sharedArea->flipExecuteCountBE) + 1);
				}

				LatteGPUState.flipCounter++;

			}
			else
			{
				// old code for all other games
				if (LatteGPUState.flipRequestCount > 0)
				{
					LatteGPUState.flipRequestCount.fetch_sub(1);
					LatteGPUState.sharedArea->flipExecuteCountBE = _swapEndianU32(_swapEndianU32(LatteGPUState.sharedArea->flipExecuteCountBE) + 1);
				}

			}
		}
		GX2::__GX2NotifyEvent(GX2::GX2CallbackEventType::FLIP);
		s_vsyncIntervalCounter = 0;
	}
	// vsync
	GX2::__GX2NotifyEvent(GX2::GX2CallbackEventType::VSYNC);
}

HRTick s_lastHostVsync = 0;

// notify when host vsync event is triggered (on renderer canvas)
void LatteTiming_NotifyHostVSync()
{
	if (!LatteTiming_IsUsingHostDrivenVSync())
		return;
	auto nowTimePoint = HighResolutionTimer::now().getTick();
	auto dif = nowTimePoint - s_lastHostVsync;
	auto vsyncPeriod = LatteTime_CalculateTimeBetweenVSync();


	if (dif < vsyncPeriod)
	{
		// skip
		return;
	}
	uint64 elapsedPeriods = dif / vsyncPeriod;
	if (elapsedPeriods >= 10)
	{
		s_lastHostVsync = nowTimePoint;
	}
	else
		s_lastHostVsync += vsyncPeriod;

	LatteTiming_signalVsync();
}

// handle timed vsync event
void LatteTiming_HandleTimedVsync()
{
	if (s_timingPaused.load(std::memory_order_relaxed))
		return;
	// simulate VSync
	uint64 currentTimer = HighResolutionTimer::now().getTick();
	if( currentTimer >= LatteGPUState.timer_nextVSync )
	{
		if(!LatteTiming_IsUsingHostDrivenVSync())
			LatteTiming_signalVsync();
		// even if vsync is delegated to the host device, we still use this virtual vsync timer to check finished states
		LatteQuery_UpdateFinishedQueries();
		LatteTextureReadback_UpdateFinishedTransfers(false);
		// update vsync timer
		uint64 vsyncTime = LatteTime_CalculateTimeBetweenVSync();
		uint64 missedVsyncCount = (currentTimer - LatteGPUState.timer_nextVSync) / vsyncTime;
		if (missedVsyncCount >= 2)
		{
			LatteGPUState.timer_nextVSync += vsyncTime*(missedVsyncCount+1ULL);
		}
		else	
			LatteGPUState.timer_nextVSync += vsyncTime;
	}
}