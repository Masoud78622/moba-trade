package com.mobatrade.core.engine

import com.mobatrade.core.halal.ShariahFilter
import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.model.Tick
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ConfluenceEngineTest {

    @BeforeEach
    fun setUp() {
        // Initialize the Shariah Filter with compliant test stocks
        ShariahFilter.initializeManual(
            symbols = listOf("TCS", "INFY", "WIPRO"),
            tokens = listOf("11536", "1594", "3787")
        )
    }

    /**
     * Helper to generate mock candle data with a specific trend or pattern.
     */
    private fun generateMockCandles(
        count: Int,
        startPrice: Double,
        trendFactor: Double = 0.0,
        volumeMultiplier: Double = 1.0
    ): List<Candle> {
        val candles = ArrayList<Candle>()
        var currentPrice = startPrice
        val now = Instant.now()
        
        for (i in 0 until count) {
            val change = currentPrice * trendFactor
            val open = currentPrice
            val close = currentPrice + change + (Math.random() - 0.5) * (startPrice * 0.005)
            val high = Math.max(open, close) + (Math.random() * (startPrice * 0.002))
            val low = Math.min(open, close) - (Math.random() * (startPrice * 0.002))
            val volume = (5000 + Math.random() * 2000 * volumeMultiplier).toLong()
            
            candles.add(
                Candle(
                    timestamp = now.minus((count - i).toLong(), ChronoUnit.MINUTES),
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume
                )
            )
            currentPrice = close
        }
        return candles
    }

    @Test
    fun testShariahComplianceFilter() {
        // TCS is Shariah compliant
        val scorerTcs = ConfluenceScorer("TCS", "IT")
        val candlesTcs = generateMockCandles(65, 3000.0, 0.001)
        val scoreTcs = scorerTcs.scoreTrade(candlesTcs)
        
        assertTrue(scoreTcs.isShariahCompliant, "TCS should be verified as Shariah compliant")
        
        // RELIANCE is NOT in our halal database list
        val scorerNonHalal = ConfluenceScorer("RELIANCE", "Energy")
        val candlesNonHalal = generateMockCandles(65, 2400.0, 0.001)
        val scoreNonHalal = scorerNonHalal.scoreTrade(candlesNonHalal)
        
        assertFalse(scoreNonHalal.isShariahCompliant, "RELIANCE must not pass the compliance check")
        assertEquals(0, scoreNonHalal.totalScore, "Non-compliant assets must receive an absolute 0 score")
        assertEquals(Direction.HOLD, scoreNonHalal.recommendedDirection, "Non-compliant assets must be HELD / skipped")
    }

    @Test
    fun testRegimeBasedScoring() {
        val scorer = ConfluenceScorer("TCS", "IT")
        
        // 1. Generate Trending Bullish candles (consecutive price gains)
        val trendingCandles = generateMockCandles(70, 3000.0, 0.003)
        val resultTrending = scorer.scoreTrade(trendingCandles)
        
        assertEquals(MarketRegime.TRENDING_BULLISH, resultTrending.marketRegime)
        
        // 2. Generate Ranging candles (flat trend, low change)
        val rangingCandles = generateMockCandles(70, 3000.0, 0.0)
        val resultRanging = scorer.scoreTrade(rangingCandles)
        
        assertEquals(MarketRegime.RANGING, resultRanging.marketRegime)
    }

    @Test
    fun testRiskManagerPositionSizing() {
        val riskManager = RiskManager(
            totalCapital = 100000.0,
            maxStandardAllocation = 20000.0,
            maxHalfAllocation = 10000.0,
            maxSingleTradeRisk = 1500.0 // Max risk ₹1500 per trade
        )

        // Scenario A: Highly confident setup (Score = 8)
        // Entry Price = ₹3000, Stop Loss = ₹2900 (Risk per share = ₹100)
        // Cap limit = 20,000 / 3000 = 6 shares
        // Risk limit = 1500 / 100 = 15 shares
        // Position size should be min(6, 15) = 6 shares
        val orderA = riskManager.evaluateAndSizeTrade(
            symbol = "TCS",
            score = 8,
            entryPrice = 3000.0,
            stopLoss = 2900.0
        )

        assertNotNull(orderA)
        assertEquals(6, orderA!!.quantity)
        assertEquals(Direction.BUY, orderA.direction)
        assertEquals(2900.0, orderA.stopLoss)
        assertEquals(3200.0, orderA.target) // Target calculated at 1:2 R:R

        // Scenario B: Wider Stop Loss to test Risk-Based Sizing limit
        // Entry Price = ₹3000, Stop Loss = ₹2700 (Risk per share = ₹300)
        // Cap limit = 20,000 / 3000 = 6 shares
        // Risk limit = 1500 / 300 = 5 shares
        // Position size should be min(6, 5) = 5 shares (limited by risk management!)
        val orderB = riskManager.evaluateAndSizeTrade(
            symbol = "INFY",
            score = 7,
            entryPrice = 3000.0,
            stopLoss = 2700.0
        )

        assertNotNull(orderB)
        assertEquals(5, orderB!!.quantity, "Position size should be restricted by risk-based limits")
    }

    @Test
    fun testRiskManagerDrawdownAndLimits() {
        val riskManager = RiskManager(
            totalCapital = 100000.0,
            maxConcurrentPositions = 2,
            maxDailyDrawdown = 3000.0
        )

        // Register two active positions
        val entryTime = Instant.now()
        riskManager.registerPosition(com.mobatrade.core.model.Position("TCS", 3000.0, 5, Direction.BUY, 2900.0, 3200.0, entryTime))
        riskManager.registerPosition(com.mobatrade.core.model.Position("INFY", 1500.0, 10, Direction.BUY, 1450.0, 1600.0, entryTime))

        // Proposal for 3rd position should be rejected (max concurrent is 2)
        val order3 = riskManager.evaluateAndSizeTrade("WIPRO", 8, 400.0, 390.0)
        assertNull(order3, "Should reject trade when concurrent positions limit is exceeded")

        // Close INFY with a massive loss to trigger drawdown limit
        // Loss: (1200 - 1500) * 10 = -₹3000
        val loss = riskManager.closePosition("INFY", 1200.0)
        assertEquals(-3000.0, loss)

        // Proposed trades should now be rejected due to daily drawdown halt
        val orderAfterDrawdown = riskManager.evaluateAndSizeTrade("TCS", 8, 3000.0, 2950.0)
        assertNull(orderAfterDrawdown, "Trading must be halted when daily drawdown threshold is breached")
    }
}
