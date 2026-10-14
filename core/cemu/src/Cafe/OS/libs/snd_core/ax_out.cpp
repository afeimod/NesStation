#include "Cafe/OS/libs/snd_core/ax.h"
#include "Cafe/Savestate/SavestateFnRegistry.h"
#include "Cafe/OS/libs/snd_core/ax_internal.h"
#include "Cafe/HW/MMU/MMU.h"
#include "audio/IAudioAPI.h"
//#include "ax.h"
#include "config/CemuConfig.h"
#include <atomic>

namespace snd_core
{
        uint32 numProcessedFrames = 0;

        void resetNumProcessedFrames()
        {
                numProcessedFrames = 0;
        }

        uint32 getNumProcessedFrames()
        {
                return numProcessedFrames;
        }

        sint32 __AXMode[AX_DEV_COUNT]; // audio mode (AX_MODE_*) per device


        bool AVMGetTVAudioMode(uint32be* tvAudioMode)
        {
                // 0 -> mono
                // 1,2 -> stereo
                // 3 -> surround
                // 4 -> unknown mode
                switch (GetConfig().tv_channels)
                {
                case kMono:
                        *tvAudioMode = 0;
                        break;
                case kSurround:
                        *tvAudioMode = 3;
                        break;
                default:
                        *tvAudioMode = 2;
                        break;
                }

                return true;
        }

        bool AVMGetDRCSystemAudioMode(uint32be* drcAudioMode)
        {
                *drcAudioMode = 1; // apparently the default is Stereo(?), MH3U exits if AXGetDeviceMode doesn't return 0 (DRCSystemAudioMode must return 1 to set DRC mode to 0)
                return true;
        }

        sint32 __AXOutTVOutputChannelCount;
        sint32 __AXOutDRCOutputChannelCount;

        void __AXSetTVMode(sint32 mode)
        {
                cemu_assert(mode == AX_MODE_STEREO || mode == AX_MODE_6CH || mode == AX_MODE_MONO);
                __AXMode[AX_DEV_TV] = mode;
        }

        void __AXSetDeviceMode(sint32 device, sint32 mode)
        {
                if (device == AX_DEV_TV)
                        __AXMode[AX_DEV_TV] = mode;
                else if (device == AX_DEV_DRC)
                        __AXMode[AX_DEV_DRC] = mode;
                else if (device == AX_DEV_RMT)
                        __AXMode[AX_DEV_RMT] = mode;
                else
                {
                        cemu_assert_debug(false);
                }
        }

        sint32 AXGetDeviceMode(sint32 device)
        {
                if (device == AX_DEV_TV || device == AX_DEV_DRC || device == AX_DEV_RMT)
                        return __AXMode[device];
                cemu_assert_debug(false);
                return 0;
        }

        void _AXOutInitDeviceModes()
        {
                // TV mode
                uint32be tvAudioMode;
                AVMGetTVAudioMode(&tvAudioMode);
                if (tvAudioMode == 0)
                {
                        // mono
                        __AXSetTVMode(AX_MODE_MONO);
                        __AXOutTVOutputChannelCount = 1;
                }
                else if (tvAudioMode == 1 || tvAudioMode == 2)
                {
                        // stereo
                        __AXSetTVMode(AX_MODE_STEREO);
                        __AXOutTVOutputChannelCount = 2;
                }
                else if (tvAudioMode == 3)
                {
                        // surround (6ch)
                        __AXSetTVMode(AX_MODE_6CH);
                        __AXOutTVOutputChannelCount = 6;
                }
                else
                {
                        assert_dbg();
                }
                // DRC mode
                uint32be drcAudioMode;
                AVMGetDRCSystemAudioMode(&drcAudioMode);
                if (drcAudioMode == 0)
                {
                        // mono
                        __AXSetDeviceMode(1, AX_MODE_MONO);
                        __AXOutDRCOutputChannelCount = 1;
                }
                else if (drcAudioMode == 2)
                {
                        // surround
                        __AXSetDeviceMode(1, AX_MODE_SURROUND);
                        __AXOutDRCOutputChannelCount = 2; // output channel count still 2 for DRC 'surround'
                }
                else if (drcAudioMode == 1)
                {
                        // stereo
                        __AXSetDeviceMode(1, AX_MODE_STEREO);
                        __AXOutDRCOutputChannelCount = 2;
                }
                else
                {
                        assert_dbg();
                }
        }

        void AXOut_Init()
        {
                _AXOutInitDeviceModes();
        }

        extern SysAllocator<sint32, AX_SAMPLES_MAX * AX_TV_CHANNEL_COUNT> __AXTVBuffer48;
        extern SysAllocator<sint32, AX_SAMPLES_MAX* AX_DRC_CHANNEL_COUNT * 2> __AXDRCBuffer48;

        sint16 __buf_AXTVDMABuffers_0[AX_SAMPLES_MAX * AX_TV_CHANNEL_COUNT];
        sint16 __buf_AXTVDMABuffers_1[AX_SAMPLES_MAX * AX_TV_CHANNEL_COUNT];
        sint16 __buf_AXTVDMABuffers_2[AX_SAMPLES_MAX * AX_TV_CHANNEL_COUNT];
        sint16* __AXTVDMABuffers[3] = {__buf_AXTVDMABuffers_0, __buf_AXTVDMABuffers_1, __buf_AXTVDMABuffers_2};

        #define AX_FRAMES_PER_GROUP             (4)

        sint16 tempTVChannelData[AX_SAMPLES_MAX * AX_TV_CHANNEL_COUNT * AX_FRAMES_PER_GROUP] = {};
        sint32 tempAudioBlockCounter = 0;


        sint16 __buf_AXDRCDMABuffers_0[AX_SAMPLES_MAX * 6];
        sint16 __buf_AXDRCDMABuffers_1[AX_SAMPLES_MAX * 6];
        sint16 __buf_AXDRCDMABuffers_2[AX_SAMPLES_MAX * 6];
        sint16* __AXDRCDMABuffers[3] = { __buf_AXDRCDMABuffers_0, __buf_AXDRCDMABuffers_1, __buf_AXDRCDMABuffers_2 };

        sint16 tempDRCChannelData[AX_SAMPLES_MAX * 6 * AX_FRAMES_PER_GROUP] = {};
        sint32 tempDRCAudioBlockCounter = 0;

        void AIInitDMA(sint16* sampleData, sint32 size)
        {
                sint32 sampleCount = size / sizeof(sint16); // sample count in total (summed up for all channels)

                if (sndGeneric.initParam.frameLength != 0)
                {
                        cemu_assert(false);
                }

                std::shared_lock lock(g_audioMutex);

                const uint32 channels = g_tvAudio ? g_tvAudio->GetChannels() : AX_TV_CHANNEL_COUNT;
                sint16* outputChannel = tempTVChannelData + AX_SAMPLES_PER_3MS_48KHZ * tempAudioBlockCounter * channels;
                for (sint32 i = 0; i < sampleCount; ++i)
                {
                        outputChannel[i] = _swapEndianS16(sampleData[i]);
                }

                tempAudioBlockCounter++;
                if (tempAudioBlockCounter == AX_FRAMES_PER_GROUP)
                {
                        if(g_tvAudio)
                                g_tvAudio->FeedBlock(tempTVChannelData);

                        tempAudioBlockCounter = 0;
                }
        }

        sint32 AIGetSamplesPerChannel(uint32 device)
        {
                // TV and DRC output the same number of samples
                return AX_SAMPLES_PER_3MS_48KHZ;
        }

        sint32 AIGetChannelCount(uint32 device)
        {
                if (__AXMode[device] == AX_MODE_6CH)
                        return 6;
                if (__AXMode[device] == AX_MODE_STEREO)
                        return 2;
                // default to mono
                return 1;
        }

        sint16* AIGetCurrentDMABuffer(uint32 device)
        {
                if (device == AX_DEV_TV)
                        return __AXTVDMABuffers[0];
                else if (device == AX_DEV_DRC)
                        return __AXDRCDMABuffers[0];
                cemu_assert_debug(false);
                return nullptr;
        }

        void AXOut_SubmitTVFrame(sint32 frameIndex)
        {
                sint32 numSamples = AIGetSamplesPerChannel(AX_DEV_TV);
                if (__AXMode[AX_DEV_TV] == AX_MODE_6CH)
                {
                        sint32* inputChannel0 = __AXTVBuffer48.GetPtr() + numSamples * 0;
                        sint32* inputChannel1 = __AXTVBuffer48.GetPtr() + numSamples * 1;
                        sint32* inputChannel2 = __AXTVBuffer48.GetPtr() + numSamples * 2;
                        sint32* inputChannel3 = __AXTVBuffer48.GetPtr() + numSamples * 3;
                        sint32* inputChannel4 = __AXTVBuffer48.GetPtr() + numSamples * 4;
                        sint32* inputChannel5 = __AXTVBuffer48.GetPtr() + numSamples * 5;
                        sint16* dmaOutputBuffer = AIGetCurrentDMABuffer(AX_DEV_TV);
                        for (sint32 i = 0; i < numSamples; i++)
                        {
                                /*
                                * DirectSound surround order
                                LEFT                            0
                                RIGHT                           1
                                SUR_LEFT                        2
                                SUR_RIGHT                       3
                                CH_FC                           4
                                CH_LFE                          5
                                =>
                                Front Left - FL                 0
                                Front Right - FR                1
                                Front Center - FC               2
                                Low Frequency - LF              3
                                Back Left - BL                  4
                                Back Right - BR                 5
                                */
                                dmaOutputBuffer[0] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel0), -32768), 32767));
                                dmaOutputBuffer[1] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel1), -32768), 32767));

                                dmaOutputBuffer[4] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel2), -32768), 32767));
                                dmaOutputBuffer[5] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel3), -32768), 32767));

                                dmaOutputBuffer[2] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel4), -32768), 32767));
                                dmaOutputBuffer[3] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel5), -32768), 32767));
                                dmaOutputBuffer += 6;
                                // next sample
                                inputChannel0++;
                                inputChannel1++;
                                inputChannel2++;
                                inputChannel3++;
                                inputChannel4++;
                                inputChannel5++;
                        }
                        AIInitDMA(__AXTVDMABuffers[frameIndex], numSamples * 6 * sizeof(sint16)); // 6ch output
                }
                else if (__AXMode[AX_DEV_TV] == AX_MODE_STEREO)
                {
                        sint32* inputChannel0 = __AXTVBuffer48.GetPtr() + numSamples * 0;
                        sint32* inputChannel1 = __AXTVBuffer48.GetPtr() + numSamples * 1;
                        sint16* dmaOutputBuffer = __AXTVDMABuffers[frameIndex];
                        for (sint32 i = 0; i < numSamples; i++)
                        {
                                dmaOutputBuffer[0] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel0), -32768), 32767));
                                dmaOutputBuffer[1] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel1), -32768), 32767));
                                dmaOutputBuffer += 2;
                                // next sample
                                inputChannel0++;
                                inputChannel1++;
                        }
                        AIInitDMA(__AXTVDMABuffers[frameIndex], numSamples * 2 * sizeof(sint16)); // 2ch output
                }
                else if (__AXMode[AX_DEV_TV] == AX_MODE_MONO)
                {
                        sint32* inputChannel0 = __AXTVBuffer48.GetPtr() + numSamples * 0;
                        sint16* dmaOutputBuffer = __AXTVDMABuffers[frameIndex];
                        for (sint32 i = 0; i < numSamples; i++)
                        {
                                dmaOutputBuffer[0] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel0), -32768), 32767));
                                dmaOutputBuffer++;
                                // next sample
                                inputChannel0++;
                        }
                        AIInitDMA(__AXTVDMABuffers[frameIndex], numSamples * 1 * sizeof(sint16)); // 1ch (output as stereo)
                }
                else
                        assert_dbg();
        }

        void AIInitDRCDMA(sint16* sampleData, sint32 size)
        {
                sint32 sampleCount = size / sizeof(sint16); // sample count in total (summed up for all channels)

                if (sndGeneric.initParam.frameLength != 0)
                {
                        cemu_assert(false);
                }

                std::shared_lock lock(g_audioMutex);

                const uint32 channels = g_padAudio ? g_padAudio->GetChannels() : AX_DRC_CHANNEL_COUNT;
                sint16* outputChannel = tempDRCChannelData + AX_SAMPLES_PER_3MS_48KHZ * tempDRCAudioBlockCounter * channels;
                for (sint32 i = 0; i < sampleCount; ++i)
                {
                        outputChannel[i] = _swapEndianS16(sampleData[i]);
                }

                tempDRCAudioBlockCounter++;
                if (tempDRCAudioBlockCounter == AX_FRAMES_PER_GROUP)
                {
                        if (g_padAudio)
                                g_padAudio->FeedBlock(tempDRCChannelData);

                        tempDRCAudioBlockCounter = 0;
                }
        }

        void AXOut_SubmitDRCFrame(sint32 frameIndex)
        {
                sint32 numSamples = AIGetSamplesPerChannel(AX_DEV_DRC);
                if (__AXMode[AX_DEV_DRC] == AX_MODE_6CH)
                {
                        sint32* inputChannel0 = __AXDRCBuffer48.GetPtr() + numSamples * 0;
                        sint32* inputChannel1 = __AXDRCBuffer48.GetPtr() + numSamples * 1;
                        sint32* inputChannel2 = __AXDRCBuffer48.GetPtr() + numSamples * 2;
                        sint32* inputChannel3 = __AXDRCBuffer48.GetPtr() + numSamples * 3;
                        sint16* dmaOutputBuffer = AIGetCurrentDMABuffer(AX_DEV_DRC);
                        for (sint32 i = 0; i < numSamples; i++)
                        {
                                dmaOutputBuffer[0] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel0), -32768), 32767));
                                dmaOutputBuffer[1] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel1), -32768), 32767));

                                dmaOutputBuffer[4] = 0;
                                dmaOutputBuffer[5] = 0;

                                dmaOutputBuffer[2] = 0;
                                dmaOutputBuffer[3] = 0;
                                dmaOutputBuffer += 6;
                                // next sample
                                inputChannel0++;
                                inputChannel1++;
                                inputChannel2++;
                                inputChannel3++;
                        }
                        AIInitDRCDMA(__AXDRCDMABuffers[frameIndex], numSamples * 6 * sizeof(sint16)); // 6ch output
                }
                else if (__AXMode[AX_DEV_DRC] == AX_MODE_STEREO)
                {
                        sint32* inputChannel0 = __AXDRCBuffer48.GetPtr() + numSamples * 0;
                        sint32* inputChannel1 = __AXDRCBuffer48.GetPtr() + numSamples * 1;
                        sint16* dmaOutputBuffer = __AXDRCDMABuffers[frameIndex];
                        for (sint32 i = 0; i < numSamples; i++)
                        {
                                dmaOutputBuffer[0] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel0), -32768), 32767));
                                dmaOutputBuffer[1] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel1), -32768), 32767));
                                dmaOutputBuffer += 2;
                                // next sample
                                inputChannel0++;
                                inputChannel1++;
                        }

                        AIInitDRCDMA(__AXDRCDMABuffers[frameIndex], numSamples * 2 * sizeof(sint16)); // 2ch output
                }
                else if (__AXMode[AX_DEV_DRC] == AX_MODE_MONO)
                {
                        sint32* inputChannel0 = __AXDRCBuffer48.GetPtr() + numSamples * 0;
                        sint16* dmaOutputBuffer = __AXDRCDMABuffers[frameIndex];
                        for (sint32 i = 0; i < numSamples; i++)
                        {
                                // write mono input as stereo output
                                dmaOutputBuffer[1] = dmaOutputBuffer[0] = _swapEndianS16((sint16)std::min(std::max(_swapEndianS32(*inputChannel0), -32768), 32767));
                                dmaOutputBuffer += 2;
                                // next sample
                                inputChannel0++;
                        }
                        AIInitDRCDMA(__AXDRCDMABuffers[frameIndex], numSamples * 2 * sizeof(sint16)); // 1ch (output as stereo)
                }
                else
                        assert_dbg();
        }

        /* AX output */

        uint32 numQueuedFramesSndGeneric = 0;

        // grace deadline (steady_clock nanoseconds) after a savestate restore:
        // the pump skips queueing new frames until it passes so the scheduler,
        // GPU and JIT can settle into the restored state before the AX IST
        // thread runs its first post-restore mixer frame. Zero = no grace
        std::atomic<sint64> s_pumpGraceDeadlineNs{ 0 };

        void AXOut_init()
        {

                numQueuedFramesSndGeneric = 0;

                std::unique_lock lock(g_audioMutex);
                if (!g_tvAudio)
                {
                        try
                        {
                                g_tvAudio = IAudioAPI::CreateDeviceFromConfig(IAudioAPI::AudioType::TV, 48000, snd_core::AX_SAMPLES_PER_3MS_48KHZ * AX_FRAMES_PER_GROUP, 16);
                        }
                        catch (std::runtime_error& ex)
                        {
                                cemuLog_log(LogType::Force, "can't initialize tv audio: {}", ex.what());
                        }
                }

                g_padVolume = GetConfig().pad_volume;
                if (!g_padAudio)
                {
                        try
                        {
                                g_padAudio = IAudioAPI::CreateDeviceFromConfig(IAudioAPI::AudioType::Gamepad, 48000, snd_core::AX_SAMPLES_PER_3MS_48KHZ * AX_FRAMES_PER_GROUP, 16);
                        }
                        catch (std::runtime_error& ex)
                        {
                                cemuLog_log(LogType::Force, "can't initialize pad audio: {}", ex.what());
                        }
                }
        }

        void AXOut_reset()
        {
                std::unique_lock lock(g_audioMutex);
                if (g_tvAudio)
                {
                        g_tvAudio->Stop();
                        g_tvAudio.reset();
                }
                if (g_padAudio)
                {
                        g_padAudio->Stop();
                        g_padAudio.reset();
                }
                if (g_portalAudio)
                {
                        g_portalAudio->Stop();
                        g_portalAudio.reset();
                }
        }

        void AXOut_updateDevicePlayState(bool isPlaying)
        {
                std::shared_lock lock(g_audioMutex);
                if (g_tvAudio)
                {
                        if (isPlaying)
                                g_tvAudio->Play();
                        else
                                g_tvAudio->Stop();
                }

                if (g_padAudio)
                {
                        if (isPlaying)
                                g_padAudio->Play();
                        else
                                g_padAudio->Stop();
                }

                if (g_portalAudio)
                {
                        if (isPlaying)
                                g_portalAudio->Play();
                        else
                                g_portalAudio->Stop();
                }
        }

        // savestate support: called after an in-place savestate restore while the
        // emulated threads are still suspended. Drops any stale queued audio,
        // restarts the host output streams and resyncs the frame gate counters so
        // the AX pump (AXOut_update) continues from a fully consistent state.
        // Without this a restore can leave the output devices buffering stale
        // blocks or with a desynced frame gate -> probabilistic silence after
        // loading a savestate.
        void AXOut_ResetAfterRestore()
        {
                // resync the frame gate: the AX pump only queues a new frame when
                // the queued counter matches the processed counter. This runs while
                // the AX pump is paused (system events frozen), so no producer can
                // race these counter resets
                {
                        std::unique_lock lock(g_audioMutex);
                        // restart the frame gate from zero on both counters: aligning
                        // against the restored numProcessedFrames would preserve the
                        // pre-restore timeline's counter offsets
                        numQueuedFramesSndGeneric = 0;
                        numProcessedFrames = 0;
                        // discard partially accumulated DMA frames so the next block
                        // starts on a fresh group boundary
                        tempAudioBlockCounter = 0;
                        tempDRCAudioBlockCounter = 0;
                }
                // hold the pump back briefly after the restore (see s_pumpGraceDeadlineNs)
                s_pumpGraceDeadlineNs.store(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count() + 50000000ll, std::memory_order_relaxed);
                // hard restart of all output devices: Stop -> Flush -> Play. This is
                // intentionally done WITHOUT holding g_audioMutex: cubeb_stream_stop
                // joins the device callback thread, and blocking on that join while
                // holding the pump lock can deadlock the restore path
                if (g_tvAudio)
                {
                        g_tvAudio->Stop();
                        g_tvAudio->Flush();
                        g_tvAudio->Play();
                }
                if (g_padAudio)
                {
                        g_padAudio->Stop();
                        g_padAudio->Flush();
                        g_padAudio->Play();
                }
                if (g_portalAudio)
                {
                        g_portalAudio->Stop();
                        g_portalAudio->Flush();
                        g_portalAudio->Play();
                }
        }

        // called periodically to check for AX updates
        void AXOut_update()
        {
                constexpr static auto kTimeout = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::milliseconds(((IAudioAPI::kBlockCount * 3) / 4) * (AX_FRAMES_PER_GROUP * 3)));
                constexpr static auto kWaitDuration = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::milliseconds(3));
                constexpr static auto kWaitDurationFast = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::microseconds(2900));
                constexpr static auto kWaitDurationMinimum = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::microseconds(1700));

                // if we haven't buffered any blocks, we will wait less time than usual
                bool additional_blocks_required = false;
                {
                        const std::shared_lock lock(g_audioMutex, std::try_to_lock);
                        if (lock)
                                additional_blocks_required = (g_tvAudio && g_tvAudio->NeedAdditionalBlocks()) || (g_padAudio && g_padAudio->NeedAdditionalBlocks());
                }

                const auto wait_duration = additional_blocks_required ? kWaitDurationFast : kWaitDuration;

                // s_ax_interval_timer increases by the wait period
                // it can lag behind by multiple periods (up to kTimeout) if there is minor stutter in the CPU thread
                // s_last_check is always set to the timestamp at the time of firing
                // it's used to enforce the minimum wait delay (we want to avoid calling AX update in quick succession because other threads may need to do work first) 

                static auto s_ax_interval_timer = now_cached() - kWaitDuration;
                static auto s_last_check = now_cached();

                const auto now = now_cached();
                const auto diff = (now - s_ax_interval_timer);

                if (diff < wait_duration)
                        return;

                // handle minimum wait time (1.7MS)
                if ((now - s_last_check) < kWaitDurationMinimum)
                        return;
                s_last_check = now;

                // if we're too far behind, skip forward
                if (diff >= kTimeout)
                        s_ax_interval_timer = (now - wait_duration);
                else
                        s_ax_interval_timer += wait_duration;


                if (snd_core::isInitialized())
                {
                        // grace window right after a savestate restore: skip queueing new
                        // frames for a short period so the first mixer frame runs well
                        // after the scheduler/GPU have settled into the restored state
                        if (s_pumpGraceDeadlineNs.load(std::memory_order_relaxed) != 0)
                        {
                                const auto nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
                                if (nowNs < s_pumpGraceDeadlineNs.load(std::memory_order_relaxed))
                                        return;
                                s_pumpGraceDeadlineNs.store(0, std::memory_order_relaxed);
                        }
                        // signed difference instead of strict equality: a frame restored from a
                        // savestate can make the processed counter briefly run ahead of the queued
                        // counter, which permanently wedged the pump (== never true) and muted all
                        // audio after an in-place restore. <= 0 self-heals within one pump period.
                        if ((sint32)numQueuedFramesSndGeneric - (sint32)snd_core::getNumProcessedFrames() <= 0)
                        {
                                AXOut_updateDevicePlayState(true);
                                snd_core::AXIst_QueueFrame();
                                numQueuedFramesSndGeneric++;
                        }
                }
        }


        // v3.7: register AX output functions for the crash reporter
        SAVESTATE_REGISTER_FN(AXOut_update)
        SAVESTATE_REGISTER_FN(AXOut_ResetAfterRestore)
        SAVESTATE_REGISTER_FN(AXOut_SubmitTVFrame)
        SAVESTATE_REGISTER_FN(AXOut_SubmitDRCFrame)
}
