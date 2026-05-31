package com.mobatrade.core.engine

import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.strategies.tier1.OpeningRangeBreakout
import com.mobatrade.core.strategies.tier1.DarvasBox
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BacktestEngineTest {

    @Test
    fun testSyntheticDataGeneration() {
        val count = 200
        val candles = MarketDataService.generateSyntheticData(
            regime = MarketRegime.TRENDING_BULLISH,
            candleCount = count,
            startPrice = 3000.0
        )

        assertNotNull(candles)
        assertEquals(count, candles.size, "Generator must yield exactly the requested number of candles")
        assertTrue(candles[0].open == 3000.0, "First candle open price should match starting price")
        
        // Assert that candles have positive structural parameters
        for (c in candles) {
            assertTrue(c.high >= c.open, "High must be greater or equal to open")
            assertTrue(c.high >= c.close, "High must be greater or equal to close")
            assertTrue(c.low <= c.open, "Low must be less or equal to open")
            assertTrue(c.low <= c.close, "Low must be less or equal to close")
            assertTrue(c.volume > 0, "Volume must be strictly positive")
        }
    }

    @Test
    fun testBacktestSimulationLoop() {
        val candles = MarketDataService.generateSyntheticData(
            regime = MarketRegime.TRENDING_BULLISH,
            candleCount = 300,
            startPrice = 1000.0
        )

        val strategy = OpeningRangeBreakout("TCS")
        val result = BacktestEngine.runBacktest(
            strategy = strategy,
            candles = candles,
            startingCapital = 100000.0,
            riskPerTradePercent = 2.0
        )

        assertNotNull(result)
        assertEquals(strategy.name, result.strategyName)
        
        // Assertions for metrics ranges
        assertTrue(result.maxDrawdownPercent >= 0.0, "Drawdown must be non-negative")
        assertTrue(result.maxDrawdownPercent <= 100.0, "Drawdown cannot exceed 100%")
        assertTrue(result.equityCurve.isNotEmpty(), "Equity curve must have datapoints")
        assertEquals(100000.0, result.equityCurve.first(), "First equity point must match starting capital")
        
        if (result.totalTrades > 0) {
            assertEquals(result.totalTrades, result.wins + result.losses, "Sum of wins and losses must equal total trades")
            assertTrue(result.winRate in 0.0..100.0, "Win rate must be inside percentage bounds")
            assertTrue(result.profitFactor >= 0.0, "Profit factor must be non-negative")
            
            // Check that simulated trades are tracked properly
            val firstTrade = result.completedTrades.first()
            assertEquals("OPEN", firstTrade.symbol) // "Opening Range Breakout" takes first 4 chars -> "OPEN"
            assertTrue(firstTrade.qty > 0, "Trade quantity must be positive")
            assertTrue(firstTrade.entryPrice > 0, "Entry price must be positive")
            assertTrue(firstTrade.exitPrice > 0, "Exit price must be positive")
            assertEquals(firstTrade.pnl > 0, firstTrade.isWin, "Win flag must match profit calculation")
        }
    }

    @Test
    fun testRangingRegimeConsolidation() {
        // Ranging regime should stay relatively bounded
        val start = 1000.0
        val candles = MarketDataService.generateSyntheticData(
            regime = MarketRegime.RANGING,
            candleCount = 300,
            startPrice = start
        )

        val finalPrice = candles.last().close
        val totalDeviationPercent = Math.abs(finalPrice - start) / start * 100.0
        
        // Due to mean-reversion force, it should stay consolidated (usually < 10% deviation over 300 candles)
        assertTrue(totalDeviationPercent < 15.0, "Ranging regime must keep prices bounded within tight consolidation bounds")
    }
}
