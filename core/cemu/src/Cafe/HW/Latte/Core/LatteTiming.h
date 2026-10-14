#pragma once

void LatteTiming_setCustomVsyncFrequency(sint32 frequency);
void LatteTiming_disableCustomVsyncFrequency();
bool LatteTiming_getCustomVsyncFrequency(sint32& customFrequency);
void LatteTiming_setSpeedMultiplier(sint32 multiplierPercent);
void LatteTiming_setPaused(bool paused);

void LatteTiming_EnableHostDrivenVSync();