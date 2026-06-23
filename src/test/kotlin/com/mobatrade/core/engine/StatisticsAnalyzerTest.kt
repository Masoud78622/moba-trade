package com.mobatrade.core.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets

class StatisticsAnalyzerTest {

    @Test
    fun testEmptyResultsFile() {
        val tempResults = File.createTempFile("temp_results_empty", ".csv")
        val tempSignals = File.createTempFile("temp_signals_empty", ".csv")
        tempResults.deleteOnExit()
        tempSignals.deleteOnExit()

        tempResults.writeText("TradeID,ExitDate,ExitPrice,ExitReason,PLPct,RMultiple,DaysToExit\n", StandardCharsets.UTF_8)

        val stats = StatisticsAnalyzer.calculateStats(tempResults, tempSignals)
        
        assertEquals(0, stats.getInt("totalTrades"))
        assertEquals(0.0, stats.getDouble("winRatePct"))
        assertEquals(0.0, stats.getDouble("profitFactor"))
        assertEquals(0.0, stats.getDouble("expectancyPct"))
        assertEquals(0.0, stats.getDouble("avgHoldTimeDays"))
        assertEquals(0, stats.getInt("maxConsecutiveLosses"))
    }

    @Test
    fun testMathematicalCalculations() {
        val tempResults = File.createTempFile("temp_results_full", ".csv")
        val tempSignals = File.createTempFile("temp_signals_full", ".csv")
        tempResults.deleteOnExit()
        tempSignals.deleteOnExit()

        val csvContent = """
            TradeID,ExitDate,ExitPrice,ExitReason,PLPct,RMultiple,DaysToExit
            T1,2026-06-01,110.0,TARGET,10.0%,2.0,4
            T2,2026-06-02,95.0,STOP,-5.0%,-1.0,2
            T3,2026-06-03,98.0,TIME,-2.0%,-0.4,10
            T4,2026-06-04,115.0,TARGET,15.0%,3.0,5
            T5,2026-06-05,99.0,STOP,-1.0%,-0.2,3
        """.trimIndent()

        tempResults.writeText(csvContent + "\n", StandardCharsets.UTF_8)

        val stats = StatisticsAnalyzer.calculateStats(tempResults, tempSignals)

        assertEquals(5, stats.getInt("totalTrades"))
        assertEquals(2, stats.getInt("wins"))
        assertEquals(3, stats.getInt("losses"))
        assertEquals(40.0, stats.getDouble("winRatePct"))
        assertEquals(12.5, stats.getDouble("avgWinPct"))
        assertEquals(-2.67, stats.getDouble("avgLossPct"))
        assertEquals(3.13, stats.getDouble("profitFactor")) // 25.0 / 8.0 = 3.125 -> rounds to 3.13
        assertEquals(3.4, stats.getDouble("expectancyPct")) // (0.40 * 12.5) + (0.60 * -2.67) = 5.0 - 1.602 = 3.398 -> rounds to 3.4
        assertEquals(4.8, stats.getDouble("avgHoldTimeDays")) // (4+2+10+5+3)/5 = 24/5 = 4.8
        assertEquals(-1.0, stats.getDouble("medianReturnPct")) // [-5.0, -2.0, -1.0, 10.0, 15.0] -> -1.0
        assertEquals(2, stats.getInt("maxConsecutiveLosses")) // T2, T3 are consecutive losses
        
        val exits = stats.getJSONObject("exitsReasonBreakdown")
        assertEquals(2, exits.getInt("TARGET"))
        assertEquals(2, exits.getInt("STOP"))
        assertEquals(1, exits.getInt("TIME"))
    }
}
