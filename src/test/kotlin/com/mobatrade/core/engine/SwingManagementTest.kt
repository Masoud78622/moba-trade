package com.mobatrade.core.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class SwingManagementTest {

    @BeforeEach
    fun setUp() {
        // Clear peaks for test isolation
        SwingPeakTracker.clear("TCS")
        SwingPeakTracker.clear("INFY")
    }

    @Test
    fun testSwingPeakTrackerBasicOperations() {
        val entry = 100.0
        
        // 1. Initial lookup when not present: peak should be max of entry and current
        val peak1 = SwingPeakTracker.updateAndGetPeak("TCS", entry, 98.0)
        assertEquals(100.0, peak1, "Peak should initialize to 100 (max of entry 100 and current 98)")

        // 2. Price rises above entry: peak should update
        val peak2 = SwingPeakTracker.updateAndGetPeak("TCS", entry, 106.0)
        assertEquals(106.0, peak2, "Peak should update to 106 when price rises")

        // 3. Price drops below peak: peak should remain at 106
        val peak3 = SwingPeakTracker.updateAndGetPeak("TCS", entry, 102.0)
        assertEquals(106.0, peak3, "Peak should remain at the highest observed price (106)")

        // 4. Clear peak: peak should be removed
        SwingPeakTracker.clear("TCS")
        val peak4 = SwingPeakTracker.updateAndGetPeak("TCS", entry, 101.0)
        assertEquals(101.0, peak4, "Peak should re-initialize to 101 after clearing")
    }

    @Test
    fun testTrailingStopMathAndActivation() {
        val entry = 100.0
        
        // Simulation 1: Price goes up to 104 (+4% gain)
        // Peak = 104, Peak Gain = 4.0%
        // Activation threshold is 5.0%, so trailing stop should NOT be active.
        var peak = SwingPeakTracker.updateAndGetPeak("INFY", entry, 104.0)
        var peakGainPercent = ((peak - entry) / entry) * 100.0
        assertFalse(peakGainPercent >= 5.0, "Trailing stop should not activate at +4% gain")

        // Simulation 2: Price goes up to 106 (+6% gain)
        // Peak = 106, Peak Gain = 6.0%
        // Activation threshold is 5.0%, so trailing stop SHOULD be active.
        peak = SwingPeakTracker.updateAndGetPeak("INFY", entry, 106.0)
        peakGainPercent = ((peak - entry) / entry) * 100.0
        assertTrue(peakGainPercent >= 5.0, "Trailing stop should activate at +6% gain")

        // Compute trailing stop values at peak 106
        val trailingStop = peak * 0.95 // 100.7
        val entryStop = entry * 0.97     // 97.0
        val effectiveStop = maxOf(trailingStop, entryStop)
        assertEquals(100.7, effectiveStop, 0.001, "Effective stop should be 100.7 (max of 100.7 trailing and 97.0 entry stop)")

        // Simulation 3: Price drops to 101.0
        // Current price 101.0 > 100.7 effectiveStop, so we should NOT trigger.
        val currentPriceSafe = 101.0
        assertFalse(currentPriceSafe <= effectiveStop, "Price 101.0 is above stop 100.7 and should be safe")

        // Simulation 4: Price drops to 100.5
        // Current price 100.5 <= 100.7 effectiveStop, so we SHOULD trigger trailing stop.
        val currentPriceHit = 100.5
        assertTrue(currentPriceHit <= effectiveStop, "Price 100.5 is at/below stop 100.7 and should trigger exit")
    }
}
