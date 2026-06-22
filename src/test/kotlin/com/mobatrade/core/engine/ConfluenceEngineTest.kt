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
        val random = java.util.Random(42)
        val candles = ArrayList<Candle>()
        var currentPrice = startPrice
        val now = Instant.now()
        
        for (i in 0 until count) {
            val change = currentPrice * trendFactor
            val open = currentPrice
            val close = currentPrice + change + (random.nextDouble() - 0.5) * (startPrice * 0.005)
            val high = Math.max(open, close) + (random.nextDouble() * (startPrice * 0.002))
            val low = Math.min(open, close) - (random.nextDouble() * (startPrice * 0.002))
            val volume = (5000 + random.nextDouble() * 2000 * volumeMultiplier).toLong()
            
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
            maxConcurrentPositions = 3,
            riskPercent = 2.0,
            maxAllocationPercent = 30.0
        )

        // Scenario A: Highly confident setup (Score = 8)
        // Entry Price = ₹3000, Stop Loss = ₹2900 (Risk per share = ₹100)
        // Cap limit = 100,000 * 30% = 30,000 / 3000 = 10 shares
        // Risk limit = 100,000 * 2% = 2,000 / 100 = 20 shares
        // Position size should be min(10, 20) = 10 shares
        // Let's pass availableCash = 100000.0
        val orderA = riskManager.evaluateAndSizeTrade(
            symbol = "TCS",
            score = 8,
            entryPrice = 3000.0,
            atr14 = 0.0,
            availableCash = 100000.0,
            fallbackStopLoss = 2900.0
        )

        assertNotNull(orderA)
        assertEquals(10, orderA!!.quantity)
        assertEquals(Direction.BUY, orderA.direction)
        assertEquals(2900.0, orderA.stopLoss)
        assertEquals(3200.0, orderA.target) // Target calculated at 1:2 R:R

        // Scenario B: Wider Stop Loss to test Risk-Based Sizing limit
        // Entry Price = ₹3000, Stop Loss = ₹2700 (Risk per share = ₹300)
        // Cap limit = 100,000 * 30% = 30,000 / 3000 = 10 shares
        // Risk limit = 100,000 * 2% = 2,000 / 300 = 6 shares
        // Position size should be min(10, 6) = 6 shares (limited by risk management!)
        val orderB = riskManager.evaluateAndSizeTrade(
            symbol = "INFY",
            score = 7,
            entryPrice = 3000.0,
            atr14 = 0.0,
            availableCash = 100000.0,
            fallbackStopLoss = 2700.0
        )

        assertNotNull(orderB)
        assertEquals(6, orderB!!.quantity, "Position size should be restricted by risk-based limits")
    }

    @Test
    fun testRiskManagerDrawdownAndLimits() {
        val riskManager = RiskManager(
            maxDailyDrawdownPercent = 3.0,
            maxConcurrentPositions = 2
        )

        // Register two active positions
        val entryTime = Instant.now()
        riskManager.registerPosition(com.mobatrade.core.model.Position("TCS", 3000.0, 5, Direction.BUY, 2900.0, 3200.0, entryTime))
        riskManager.registerPosition(com.mobatrade.core.model.Position("INFY", 1500.0, 10, Direction.BUY, 1450.0, 1600.0, entryTime))

        // Proposal for 3rd position should be rejected (max concurrent is 2)
        val order3 = riskManager.evaluateAndSizeTrade("WIPRO", 8, 400.0, 0.0, 100000.0, 390.0)
        assertNull(order3, "Should reject trade when concurrent positions limit is exceeded")

        // Close INFY with a massive loss to trigger drawdown limit
        // Loss: (1200 - 1500) * 10 = -₹3000
        val loss = riskManager.closePosition("INFY", 1200.0)
        assertEquals(-3000.0, loss)

        // Proposed trades should now be rejected due to daily drawdown halt
        val orderAfterDrawdown = riskManager.evaluateAndSizeTrade("TCS", 8, 3000.0, 0.0, 100000.0, 2950.0)
        assertNull(orderAfterDrawdown, "Trading must be halted when daily drawdown threshold is breached")
    }

    @Test
    fun printScores() {
        // Seed News Sentiment and Sector Rotation
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("TCS", 0.85)
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("INFY", 0.82)
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("WIPRO", 0.78)
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("HCLTECH", 0.81)

        com.mobatrade.core.strategies.tier4.SectorRotation.updateSectorScores(
            mapOf("IT" to 1.12, "PHARMA" to 1.08, "FMCG" to 1.02)
        )

        val majorStocks = listOf(
            Triple("TCS", "IT", 3045.00),
            Triple("INFY", "IT", 1520.50),
            Triple("WIPRO", "IT", 460.25),
            Triple("RELIANCE", "ENERGY", 2450.0),
            Triple("HCLTECH", "IT", 1300.00)
        )
        for ((symbol, sector, startPrice) in majorStocks) {
            val candles = MarketDataService.generateSyntheticData(
                regime = MarketRegime.TRENDING_BULLISH,
                candleCount = 100,
                startPrice = startPrice
            )
            val scorer = ConfluenceScorer(symbol, sector)
            val scored = scorer.scoreTrade(candles)
            println("SYMBOL: $symbol, SCORE: ${scored.totalScore}, DIRECTION: ${scored.recommendedDirection}, TRIGGERS: ${scored.triggers}")
        }
    }

    @Test
    fun testRealCandlesScoring() {
        val loginSuccess = AngelOneClient.login(
            tradingPassword = "3112",
            totpSecret = "K336YHYAV6NN5H2DYMPBBZ55NM"
        )
        assertTrue(loginSuccess, "Login must succeed to run real candles test")
        val fetchResult = kotlinx.coroutines.runBlocking {
            AngelOneClient.fetchHistoricalCandles(
                symbolToken = "11536",
                symbol = "TCS"
            )
        }
        val candles = if (fetchResult is com.mobatrade.core.model.FetchResult.Success) fetchResult.data else emptyList()
        assertFalse(candles.isEmpty(), "Candles must not be empty")
        
        val scorer = ConfluenceScorer("TCS", "IT")
        val scored = scorer.scoreTrade(candles)
        println("=== REAL CANDLE SCORING TEST ===")
        println("Symbol: ${scored.symbol}, Score: ${scored.totalScore}, Direction: ${scored.recommendedDirection}")
        println("Triggers: ${scored.triggers}")
        assertNotNull(scored)
    }

    @Test
    fun testVwapReclaimStrategy() {
        val symbol = "TCS"
        val IST = java.time.ZoneId.of("Asia/Kolkata")
        val today = java.time.LocalDate.now(IST)
        
        // Construct 24 candles starting at 9:15 AM today
        val baseTime = today.atTime(9, 15).atZone(IST).toInstant()
        val candles = ArrayList<Candle>()
        
        // 1. Initial 20 flat candles to establish a baseline VWAP and volume
        val currentPrice = 3000.0
        val baseVolume = 1000L
        for (i in 0 until 20) {
            candles.add(
                Candle(
                    timestamp = baseTime.plus((i * 5).toLong(), java.time.temporal.ChronoUnit.MINUTES),
                    open = currentPrice,
                    high = currentPrice + 5.0,
                    low = currentPrice - 5.0,
                    close = currentPrice,
                    volume = baseVolume
                )
            )
        }
        
        // 2. Dip below VWAP and consolidate with shrinking volume (3 candles)
        candles.add(
            Candle(
                timestamp = baseTime.plus((20 * 5).toLong(), java.time.temporal.ChronoUnit.MINUTES),
                open = 3000.0,
                high = 3000.0,
                low = 2980.0,
                close = 2985.0,
                volume = 900L
            )
        )
        
        candles.add(
            Candle(
                timestamp = baseTime.plus((21 * 5).toLong(), java.time.temporal.ChronoUnit.MINUTES),
                open = 2985.0,
                high = 2990.0,
                low = 2975.0,
                close = 2980.0,
                volume = 800L
            )
        )
        
        candles.add(
            Candle(
                timestamp = baseTime.plus((22 * 5).toLong(), java.time.temporal.ChronoUnit.MINUTES),
                open = 2980.0,
                high = 2985.0,
                low = 2970.0,
                close = 2975.0,
                volume = 700L
            )
        )
        
        // 3. Reclaim candle (Candle 23): closes back above VWAP on high volume (> 1.3x average volume)
        candles.add(
            Candle(
                timestamp = baseTime.plus((23 * 5).toLong(), java.time.temporal.ChronoUnit.MINUTES),
                open = 2975.0,
                high = 3020.0,
                low = 2972.0,
                close = 3015.0,
                volume = 2000L
            )
        )
        
        val strategy = com.mobatrade.core.strategies.tier2.VwapReclaim(symbol)
        val signal = strategy.evaluate(candles)
        
        assertNotNull(signal, "VWAP Reclaim should trigger a buy signal")
        assertEquals(Direction.BUY, signal!!.direction)
        assertEquals("VWAP Reclaim", signal.strategyName)
        assertEquals(3015.0, signal.triggerPrice)
        assertEquals(2972.0, signal.metadata["stopLoss"])
    }
}
