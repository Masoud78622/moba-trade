package com.mobatrade.core.strategies.tier1

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Signal
import com.mobatrade.core.model.Tick
import com.mobatrade.core.strategies.Strategy
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime

/**
 * S1: Opening Range Breakout (ORB)
 * Enforces a strict long-only breakout above the high of the first candle of the day.
 */
class OpeningRangeBreakout(
    val symbol: String,
    private val timeframeMinutes: Int = 15
) : Strategy {
    override val name: String = "Opening Range Breakout"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 2) return null
        
        // Find the opening candle of today
        val todayOpeningCandle = findOpeningCandle(candles) ?: return null
        val openingHigh = todayOpeningCandle.high
        val averageVolume = candles.takeLast(10).map { it.volume }.average()
        
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        val currentVolume = latestCandle.volume
        
        // Breakout condition: current price breaks opening high AND volume is above average
        if (currentPrice > openingHigh && currentVolume > averageVolume * 1.3) {
            // Check that we haven't broken out too far already
            val percentAbove = (currentPrice - openingHigh) / openingHigh
            if (percentAbove < 0.02) { // Enter within 2% of breakout
                return Signal(
                    symbol = symbol,
                    direction = Direction.BUY,
                    score = 2, // Confluence score weight
                    strategyName = name,
                    triggerPrice = currentPrice,
                    timestamp = Instant.now(),
                    metadata = mapOf(
                        "openingHigh" to openingHigh,
                        "openingLow" to todayOpeningCandle.low,
                        "breakoutVolume" to currentVolume,
                        "averageVolume" to averageVolume
                    )
                )
            }
        }
        
        return null
    }

    private fun findOpeningCandle(candles: List<Candle>): Candle? {
        val zone = ZoneId.of("Asia/Kolkata") // Defaulting to Indian market (9:15 AM)
        val today = Instant.now().atZone(zone).toLocalDate()
        
        return candles.firstOrNull { candle ->
            val dateTime = candle.timestamp.atZone(zone)
            dateTime.toLocalDate() == today && 
            dateTime.toLocalTime() >= LocalTime.of(9, 15) &&
            dateTime.toLocalTime() <= LocalTime.of(9, 15).plusMinutes(timeframeMinutes.toLong())
        }
    }
}

/**
 * S2: Darvas Box (Box Theory)
 * Rules:
 * 1. Checks if stock is trending / making recent period highs (e.g. 50 periods).
 * 2. Establishes a consolidate box high and box low.
 * 3. Volume dries up inside the box.
 * 4. Breakout above box high with high volume = BUY.
 */
class DarvasBox(
    val symbol: String,
    private val period: Int = 20
) : Strategy {
    override val name: String = "Darvas Box"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < period + 5) return null
        
        // 1. Establish the Darvas Box boundaries
        var boxHigh = Double.MIN_VALUE
        var boxLow = Double.MAX_VALUE
        
        // Inspect consolidation period prior to the last candle
        val consolidationRange = candles.subList(candles.size - period - 1, candles.size - 1)
        for (c in consolidationRange) {
            if (c.high > boxHigh) boxHigh = c.high
            if (c.low < boxLow) boxLow = c.low
        }
        
        val boxHeight = boxHigh - boxLow
        val boxThreshold = boxHigh * 0.05 // Box height should be tight (< 5% of price)
        if (boxHeight > boxThreshold) return null // Box is too wide, not a tight consolidation
        
        // 2. Check if volume dried up inside the box compared to historical volume
        val consolidationVolume = consolidationRange.map { it.volume.toDouble() }.average()
        val historicalVolume = candles.subList(0, candles.size - period - 1).takeLast(30).map { it.volume.toDouble() }.average()
        
        if (consolidationVolume > historicalVolume * 0.9) return null // Volume did not dry up
        
        // 3. Evaluate the breakout candle (the latest candle)
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        if (currentPrice > boxHigh && latestCandle.volume > historicalVolume * 1.5) {
            val stopLoss = boxLow
            val target = boxHigh + boxHeight
            
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 3, // Highly reliable in Halal stocks (IT / Pharma)
                strategyName = name,
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf(
                    "boxHigh" to boxHigh,
                    "boxLow" to boxLow,
                    "stopLoss" to stopLoss,
                    "target" to target,
                    "volumeSurge" to (latestCandle.volume.toDouble() / historicalVolume)
                )
            )
        }
        
        return null
    }
}

/**
 * S3: Support/Resistance Flip
 * Detects when a previous structural resistance is broken, retraced to, and serves as new support.
 */
class SupportResistanceFlip(
    val symbol: String,
    private val period: Int = 30
) : Strategy {
    override val name: String = "Support/Resistance Flip"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < period + 5) return null
        
        // 1. Find previous major resistance (recent peak in historical candles)
        val historical = candles.subList(0, candles.size - 5)
        var resistance = Double.MIN_VALUE
        for (c in historical) {
            if (c.high > resistance) {
                resistance = c.high
            }
        }
        
        // 2. Check if price recently broke above this resistance
        val recentPath = candles.subList(candles.size - 5, candles.size)
        var hasBrokenAbove = false
        for (c in recentPath) {
            if (c.close > resistance) {
                hasBrokenAbove = true
                break
            }
        }
        
        if (!hasBrokenAbove) return null
        
        // 3. Check if the current price has retraced down to the resistance level (testing as support)
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        // Re-test zone: within 0.5% of the old resistance
        val lowerBound = resistance * 0.995
        val upperBound = resistance * 1.01
        
        if (currentPrice in lowerBound..upperBound) {
            // Reversal confirmation: Look for a bullish candlestick pattern (e.g., Hammer or Bullish Engulfing)
            if (isBullishReversalCandle(latestCandle)) {
                return Signal(
                    symbol = symbol,
                    direction = Direction.BUY,
                    score = 2,
                    strategyName = name,
                    triggerPrice = currentPrice,
                    timestamp = Instant.now(),
                    metadata = mapOf(
                        "oldResistance" to resistance,
                        "testPrice" to currentPrice
                    )
                )
            }
        }
        
        return null
    }

    private fun isBullishReversalCandle(c: Candle): Boolean {
        val body = Math.abs(c.close - c.open)
        val totalRange = c.high - c.low
        if (totalRange == 0.0) return false
        
        val lowerShadow = Math.min(c.open, c.close) - c.low
        val upperShadow = c.high - Math.max(c.open, c.close)
        
        // Hammer detection: Lower shadow is at least 2x the body, upper shadow is small
        val isHammer = lowerShadow > (2 * body) && upperShadow < (0.5 * body)
        // Strong green candle close near high
        val isStrongGreen = c.close > c.open && (c.high - c.close) < (0.1 * totalRange)
        
        return isHammer || isStrongGreen
    }
}
