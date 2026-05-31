package com.mobatrade.core.strategies.tier3

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Signal
import com.mobatrade.core.model.Tick
import com.mobatrade.core.strategies.Strategy
import java.time.Instant

/**
 * S7: Order Block Detection
 * Detects Bullish Order Blocks: The last bearish candle before a strong, volume-backed bullish breakout.
 * Buy Signal: Price retraces/mitigates the top boundary of this Order Block.
 */
class OrderBlocks(
    val symbol: String
) : Strategy {
    override val name: String = "Order Block Detection"

    data class OrderBlock(val top: Double, val bottom: Double, val timestamp: Instant, var mitigated: Boolean = false)

    private val activeOrderBlocks = ArrayList<OrderBlock>()

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 5) return null

        // 1. Scan for new Order Blocks in the recent candles (looking back up to 5 candles)
        for (i in candles.size - 5 until candles.size - 2) {
            val candle1 = candles[i]     // Potential OB candle (must be bearish)
            val candle2 = candles[i + 1] // Breakout candle (must be strongly bullish)
            val candle3 = candles[i + 2] // Follow-through candle
            
            if (candle1.close < candle1.open) { // Candle 1 is Bearish
                // Candle 2 must be highly bullish and volume must be above average
                val avgVolume = candles.take(i).takeLast(20).map { it.volume }.average()
                val isStrongBullish = candle2.close > candle2.open && 
                                      (candle2.close - candle2.open) > (candle1.open - candle1.close) * 1.5 &&
                                      candle2.volume > avgVolume * 1.2
                
                if (isStrongBullish) {
                    val ob = OrderBlock(
                        top = candle1.high, // Top of the OB candle body or high wick
                        bottom = candle1.low,
                        timestamp = candle1.timestamp
                    )
                    
                    // Add if we don't already have it
                    if (activeOrderBlocks.none { it.timestamp == ob.timestamp }) {
                        activeOrderBlocks.add(ob)
                    }
                }
            }
        }

        // Clean up mitigated order blocks
        activeOrderBlocks.removeIf { it.mitigated }

        // 2. Check if the current price is mitigating one of the active order blocks
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close

        for (ob in activeOrderBlocks) {
            // Price returns to the order block zone (mitigation)
            if (currentPrice in ob.bottom..ob.top) {
                ob.mitigated = true // Mark as mitigated so we don't trigger again
                
                return Signal(
                    symbol = symbol,
                    direction = Direction.BUY,
                    score = 3, // Premium SMC confirmation
                    strategyName = name,
                    triggerPrice = currentPrice,
                    timestamp = Instant.now(),
                    metadata = mapOf(
                        "obTop" to ob.top,
                        "obBottom" to ob.bottom,
                        "obTime" to ob.timestamp
                    )
                )
            }
        }

        return null
    }
}

/**
 * S8: Break of Structure (BOS)
 * Identifies structural shifts. In an uptrend, when price breaks above a previous swing high (high of the structure),
 * it confirms structural strength.
 * Buy Signal: Price successfully closes above the previous swing high, confirming a trend continuation.
 */
class BreakOfStructure(
    val symbol: String,
    private val swingLookback: Int = 20
) : Strategy {
    override val name: String = "Break of Structure"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < swingLookback + 5) return null

        // 1. Find the previous major swing high in the structure
        var previousSwingHigh = Double.MIN_VALUE
        var swingHighIndex = -1
        
        // Exclude the most recent 5 candles to ensure we are looking at established structure
        val structureRange = candles.subList(candles.size - swingLookback - 5, candles.size - 5)
        for (i in 1 until structureRange.size - 1) {
            val prev = structureRange[i - 1]
            val curr = structureRange[i]
            val next = structureRange[i + 1]
            
            if (curr.high > prev.high && curr.high > next.high) {
                if (curr.high > previousSwingHigh) {
                    previousSwingHigh = curr.high
                    swingHighIndex = candles.indexOf(curr)
                }
            }
        }

        if (swingHighIndex == -1) return null

        // 2. Check if the latest candles have broken above this previous swing high (BOS)
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        val prevClose = candles[candles.size - 2].close

        // BOS occurs when the price breaks and closes above the previous swing high
        if (prevClose < previousSwingHigh && currentPrice > previousSwingHigh) {
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 2,
                strategyName = name,
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf(
                    "brokenSwingHigh" to previousSwingHigh,
                    "bosPrice" to currentPrice
                )
            )
        }

        return null
    }
}

/**
 * S9: Fair Value Gap (FVG)
 * Identifies a 3-candle imbalance where there is an empty space between Candle 1 High and Candle 3 Low.
 * Buy Signal: Price retraces back down into this bullish imbalance.
 */
class FairValueGap(
    val symbol: String
) : Strategy {
    override val name: String = "Fair Value Gap"

    data class FvgZone(val top: Double, val bottom: Double, val timestamp: Instant, var filled: Boolean = false)

    private val activeGaps = ArrayList<FvgZone>()

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 4) return null

        // 1. Scan for new Bullish FVGs (between indices candles.size - 4, candles.size - 3, candles.size - 2)
        val c1 = candles[candles.size - 4]
        val c2 = candles[candles.size - 3]
        val c3 = candles[candles.size - 2]

        // Bullish FVG: Candle 2 is strongly bullish and Candle 1 High is LESS than Candle 3 Low
        if (c2.close > c2.open && c1.high < c3.low) {
            val gap = FvgZone(
                bottom = c1.high,
                top = c3.low,
                timestamp = c2.timestamp
            )
            
            if (activeGaps.none { it.timestamp == gap.timestamp }) {
                activeGaps.add(gap)
            }
        }

        // Clean up filled gaps
        activeGaps.removeIf { it.filled }

        // 2. Check if the latest price has retraced down to fill the gap (Buy entry)
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close

        for (gap in activeGaps) {
            // Price dips into the FVG zone
            if (currentPrice in gap.bottom..gap.top) {
                gap.filled = true // Mark as filled so we don't double trigger
                
                return Signal(
                    symbol = symbol,
                    direction = Direction.BUY,
                    score = 2,
                    strategyName = name,
                    triggerPrice = currentPrice,
                    timestamp = Instant.now(),
                    metadata = mapOf(
                        "gapTop" to gap.top,
                        "gapBottom" to gap.bottom,
                        "fvgTime" to gap.timestamp
                    )
                )
            }
        }

        return null
    }
}

/**
 * S10: Liquidity Sweep
 * Identifies stop hunts: Price sweeps below a previous swing low (support) to trigger stop losses,
 * and immediately reverses back up.
 * Buy Signal: Reversal candle close back above the swept swing low level.
 */
class LiquiditySweep(
    val symbol: String,
    private val lookback: Int = 15
) : Strategy {
    override val name: String = "Liquidity Sweep"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 35) return null

        // 1. Volatility Filter (ADX): Only trade reversals when ADX < 25 (ranging environment)
        val adxResult = com.mobatrade.core.strategies.tier4.TechIndicators.calculateAdx(candles, 14)
        if (adxResult.adx.isEmpty()) return null
        val currentAdx = adxResult.adx.last()
        if (currentAdx >= 25.0) return null

        // 2. Find recent support (lowest low in historical window, excluding the last 3 candles)
        var supportLevel = Double.MAX_VALUE
        val history = candles.subList(candles.size - lookback - 3, candles.size - 3)
        for (c in history) {
            if (c.low < supportLevel) {
                supportLevel = c.low
            }
        }

        // 3. Look for the sweep and reversal in the last 2 candles
        val lastCandle = candles[candles.size - 2]
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close

        // Condition: The last candle swept below the support level (low < supportLevel),
        // but either it or the current candle closed back ABOVE the support level, forming a bullish reversal.
        val swept = lastCandle.low < supportLevel
        val recovered = latestCandle.close > supportLevel && latestCandle.close > latestCandle.open

        if (swept && recovered && currentPrice > supportLevel) {
            val atr = calculateAtr(candles, 14)
            if (atr > 0.0) {
                // Stop loss = entryPrice - 1.5 * ATR
                val stopLoss = currentPrice - (atr * 1.5)
                // Target = entryPrice + 1.5 * ATR * 3.0 (enforcing a strict 3:1 Reward-to-Risk ratio)
                val target = currentPrice + (atr * 1.5 * 3.0)

                return Signal(
                    symbol = symbol,
                    direction = Direction.BUY,
                    score = 3, // Highly powerful confluence trigger
                    strategyName = name,
                    triggerPrice = currentPrice,
                    timestamp = Instant.now(),
                    metadata = mapOf(
                        "sweptSupport" to supportLevel,
                        "sweepLow" to lastCandle.low,
                        "recoveryPrice" to latestCandle.close,
                        "adx" to currentAdx,
                        "atr" to atr,
                        "stopLoss" to stopLoss,
                        "target" to target
                    )
                )
            }
        }

        return null
    }

    private fun calculateAtr(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period + 1) return 0.0
        var trSum = 0.0
        for (i in candles.size - period until candles.size) {
            val curr = candles[i]
            val prev = candles[i - 1]
            val hL = curr.high - curr.low
            val hCp = Math.abs(curr.high - prev.close)
            val lCp = Math.abs(curr.low - prev.close)
            trSum += Math.max(hL, Math.max(hCp, lCp))
        }
        return trSum / period
    }
}
