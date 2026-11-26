package com.nesstation.app.battle

import org.junit.Assert.assertEquals
import org.junit.Test

class NetplayFrameMappingTest {
    @Test
    fun remoteNetworkFrameMapsToExecutedFrame() {
        assertEquals(7L, networkInputToExecutionFrame(9L, 2))
    }

    @Test
    fun delayedWarmupFramesMapBeforeExecutionStarts() {
        assertEquals(-2L, networkInputToExecutionFrame(0L, 2))
        assertEquals(0L, networkInputToExecutionFrame(2L, 2))
    }

    @Test
    fun inputDelayIsClampedToNegotiatedRange() {
        assertEquals(4L, networkInputToExecutionFrame(4L, -3))
        assertEquals(-12L, networkInputToExecutionFrame(4L, 16))
    }
}
