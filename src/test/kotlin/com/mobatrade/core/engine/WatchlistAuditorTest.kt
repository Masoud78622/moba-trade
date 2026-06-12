package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class WatchlistAuditorTest {

    private fun generateMockCandles(
        count: Int,
        startPrice: Double,
        trendFactor: Double = 0.0,
        volume: Long = 600_000
    ): List<Candle> {
        val candles = ArrayList<Candle>()
        var currentPrice = startPrice
        val now = Instant.now()
        
        for (i in 0 until count) {
            val change = currentPrice * trendFactor
            val open = currentPrice
            val close = currentPrice + change
            // Keep high/low close to close for deterministic testing
            val high = maxOf(open, close) * 1.01
            val low = minOf(open, close) * 0.99
            
            candles.add(
                Candle(
                    timestamp = now.minus((count - i).toLong(), ChronoUnit.DAYS),
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
    fun testAuditPassesAllCriteria() {
        // Generates an upward trending stock starting at ₹2000
        val candles = generateMockCandles(250, 2000.0, 0.001, volume = 600_000)
        val lastPrice = candles.last().close // around ₹2566
        
        // Pass everything matching the requirements
        val passed = WatchlistAuditor.passesAudit(
            candles = candles,
            avgDailyVolume = 600_000.0,
            avgDailyValueTraded = 600_000.0 * lastPrice, // > ₹2 crore
            ema50Daily = lastPrice * 0.95, // EMA50 > EMA200
            ema200Daily = lastPrice * 0.85, // Price > EMA200
            atr14 = lastPrice * 0.02, // ATR is 2% of price
            week52Low = 2000.0, // price is 28% off lows (passes > 1.20)
            week52High = lastPrice * 1.05 // price within 15% of 52W high (passes > 0.85)
        )

        assertTrue(passed, "Stock satisfying all criteria should pass the audit")
    }

    @Test
    fun testAuditFailsLowLiquidity() {
        val candles = generateMockCandles(250, 2000.0, 0.001, volume = 100_000)
        val lastPrice = candles.last().close
        
        // Fails: Volume is 100k (< 500k)
        val passed = WatchlistAuditor.passesAudit(
            candles = candles,
            avgDailyVolume = 100_000.0,
            avgDailyValueTraded = 100_000.0 * lastPrice,
            ema50Daily = lastPrice * 0.95,
            ema200Daily = lastPrice * 0.85,
            atr14 = lastPrice * 0.02,
            week52Low = 2000.0,
            week52High = lastPrice * 1.05
        )

        assertFalse(passed, "Stock with low average daily volume should fail the audit")
    }

    @Test
    fun testAuditFailsPennyPrice() {
        val candles = generateMockCandles(250, 50.0, 0.001, volume = 600_000)
        val lastPrice = candles.last().close // around ₹64
        
        // Fails: Price is ₹64 (< 100.0)
        val passed = WatchlistAuditor.passesAudit(
            candles = candles,
            avgDailyVolume = 600_000.0,
            avgDailyValueTraded = 600_000.0 * lastPrice,
            ema50Daily = lastPrice * 0.95,
            ema200Daily = lastPrice * 0.85,
            atr14 = lastPrice * 0.02,
            week52Low = 50.0,
            week52High = lastPrice * 1.05
        )

        assertFalse(passed, "Penny stock under ₹100 should fail the audit")
    }

    @Test
    fun testAuditFailsBearishTrend() {
        val candles = generateMockCandles(250, 2000.0, -0.001, volume = 600_000)
        val lastPrice = candles.last().close // around ₹1550
        
        // Fails: Price < EMA200
        val passed = WatchlistAuditor.passesAudit(
            candles = candles,
            avgDailyVolume = 600_000.0,
            avgDailyValueTraded = 600_000.0 * lastPrice,
            ema50Daily = lastPrice * 1.05,
            ema200Daily = lastPrice * 1.15, // price is below EMA200
            atr14 = lastPrice * 0.02,
            week52Low = lastPrice * 0.9,
            week52High = 2000.0
        )

        assertFalse(passed, "Stock below 200 EMA should fail the audit")
    }

    @Test
    fun testAuditFailsExtendedBelow52WHigh() {
        val candles = generateMockCandles(250, 2000.0, 0.001, volume = 600_000)
        val lastPrice = candles.last().close
        
        // Fails: Price is far below 52-week high (< 0.85 * week52High)
        val passed = WatchlistAuditor.passesAudit(
            candles = candles,
            avgDailyVolume = 600_000.0,
            avgDailyValueTraded = 600_000.0 * lastPrice,
            ema50Daily = lastPrice * 0.95,
            ema200Daily = lastPrice * 0.85,
            atr14 = lastPrice * 0.02,
            week52Low = 2000.0,
            week52High = lastPrice * 1.30 // lastPrice is 76% of 52W high (fails < 85%)
        )

        assertFalse(passed, "Stock extended too far below its 52W high should fail the audit")
    }

    @Test
    fun testGetMostRecentSaturday() {
        val sat = java.time.LocalDate.of(2026, 6, 13) // Saturday
        val sun = java.time.LocalDate.of(2026, 6, 14) // Sunday
        val mon = java.time.LocalDate.of(2026, 6, 15) // Monday
        val wed = java.time.LocalDate.of(2026, 6, 17) // Wednesday

        assertEquals(sat, WatchlistAuditor.getMostRecentSaturday(sat))
        assertEquals(sat, WatchlistAuditor.getMostRecentSaturday(sun))
        assertEquals(sat, WatchlistAuditor.getMostRecentSaturday(mon))
        assertEquals(sat, WatchlistAuditor.getMostRecentSaturday(wed))
    }
}

