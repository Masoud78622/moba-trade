package com.mobatrade.core.strategies.tier2

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Signal
import com.mobatrade.core.model.Tick
import com.mobatrade.core.strategies.Strategy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId


/**
 * S4: Volume Profile (POC, VAH, VAL)
 * Evaluates the volume distribution across price bins.
 * Buy Signal: Price is below Value Area Low (VAL), indicating undervaluation, and turns back up towards the Point of Control (POC).
 */
class VolumeProfile(
    val symbol: String,
    private val numBins: Int = 30
) : Strategy {
    override val name: String = "Volume Profile"

    data class ProfileLevels(val poc: Double, val vah: Double, val valLevel: Double)

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 60) return null
        
        val levels = calculateProfile(candles) ?: return null
        val latestCandle = candles.last()
        val prevCandle = candles[candles.size - 2]
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        // 1. Trend Filter: Short-term bullish (EMA 20 > EMA 50)
        val prices = candles.map { it.close }
        val ema20List = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(prices, 20)
        val ema50List = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(prices, 50)
        if (ema20List.size < 2 || ema50List.size < 2) return null
        val ema20 = ema20List.last()
        val ema50 = ema50List.last()
        if (ema20 <= ema50) return null
        
        // 2. Volume Spike: Volume must be at least 1.5x of 20-period average volume
        val avgVolume = candles.takeLast(20).map { it.volume.toDouble() }.average()
        if (latestCandle.volume < avgVolume * 1.5) return null
        
        // 3. Flipped Confirmation Entry Logic:
        // Enter when previous candle closed below VAL, and current price has returned into Value Area from below (above VAL).
        val prevBelowVal = prevCandle.close < levels.valLevel
        val currentAboveVal = currentPrice >= levels.valLevel && currentPrice <= levels.poc
        
        if (prevBelowVal && currentAboveVal) {
            // Stop loss 1% below VAL
            val stopLoss = levels.valLevel * 0.99
            val risk = currentPrice - stopLoss
            
            if (risk > 0.0) {
                // Target enforced at minimum 2.5:1 RR ratio
                val target = currentPrice + (risk * 2.5)
                
                return Signal(
                    symbol = symbol,
                    direction = Direction.BUY,
                    score = 2,
                    strategyName = name,
                    triggerPrice = currentPrice,
                    timestamp = Instant.now(),
                    metadata = mapOf(
                        "poc" to levels.poc,
                        "vah" to levels.vah,
                        "val" to levels.valLevel,
                        "currentPrice" to currentPrice,
                        "stopLoss" to stopLoss,
                        "target" to target
                    )
                )
            }
        }
        
        return null
    }

    fun calculateProfile(candles: List<Candle>): ProfileLevels? {
        if (candles.isEmpty()) return null
        
        val minPrice = candles.minOf { it.low }
        val maxPrice = candles.maxOf { it.high }
        val priceRange = maxPrice - minPrice
        if (priceRange == 0.0) return null
        
        val binSize = priceRange / numBins
        val bins = DoubleArray(numBins) // Holds volume per bin
        
        // Populate bins using each candle's high/low/close and volume
        for (c in candles) {
            val avgPrice = (c.high + c.low + c.close) / 3.0
            val binIndex = ((avgPrice - minPrice) / binSize).toInt().coerceIn(0, numBins - 1)
            bins[binIndex] += c.volume.toDouble()
        }
        
        // 1. Find POC (Bin index with maximum volume)
        var maxVolumeIndex = 0
        var maxVolume = 0.0
        var totalVolume = 0.0
        for (i in bins.indices) {
            totalVolume += bins[i]
            if (bins[i] > maxVolume) {
                maxVolume = bins[i]
                maxVolumeIndex = i
            }
        }
        
        val pocPrice = minPrice + (maxVolumeIndex * binSize) + (binSize / 2.0)
        
        // 2. Find VAH and VAL (Value Area: range containing 70% of total volume centered around POC)
        val targetValueVolume = totalVolume * 0.70
        var accumulatedVolume = bins[maxVolumeIndex]
        var upperIndex = maxVolumeIndex
        var lowerIndex = maxVolumeIndex
        
        while (accumulatedVolume < targetValueVolume) {
            val upperVol = if (upperIndex + 1 < numBins) bins[upperIndex + 1] else 0.0
            val lowerVol = if (lowerIndex - 1 >= 0) bins[lowerIndex - 1] else 0.0
            
            if (upperVol == 0.0 && lowerVol == 0.0) break
            
            if (upperVol >= lowerVol) {
                upperIndex++
                accumulatedVolume += upperVol
            } else {
                lowerIndex--
                accumulatedVolume += lowerVol
            }
        }
        
        val valPrice = minPrice + (lowerIndex * binSize)
        val vahPrice = minPrice + (upperIndex * binSize) + binSize
        
        return ProfileLevels(poc = pocPrice, vah = vahPrice, valLevel = valPrice)
    }
}

/**
 * S5: VWAP + Dev Bands (Volume Weighted Average Price)
 * Tracks deviation bands from VWAP.
 * Buy Signal: Price falls to or below -2 Standard Deviations band (oversold) and rebounds.
 */
class VwapDevBands(
    val symbol: String
) : Strategy {
    override val name: String = "VWAP Dev Bands"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 20) return null
        
        // Calculate VWAP
        var cumulativeTypicalPriceVolume = 0.0
        var cumulativeVolume = 0.0
        
        for (c in candles) {
            val typicalPrice = (c.high + c.low + c.close) / 3.0
            cumulativeTypicalPriceVolume += typicalPrice * c.volume
            cumulativeVolume += c.volume
        }
        
        if (cumulativeVolume == 0.0) return null
        val vwap = cumulativeTypicalPriceVolume / cumulativeVolume
        
        // Calculate Standard Deviation of prices relative to VWAP
        var varianceSum = 0.0
        for (c in candles) {
            val typicalPrice = (c.high + c.low + c.close) / 3.0
            varianceSum += Math.pow(typicalPrice - vwap, 2.0)
        }
        val stdDev = Math.sqrt(varianceSum / candles.size)
        
        val lowerBand1_5 = vwap - (1.5 * stdDev)
        
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        // If price is already above VWAP, no mean-reversion entry needed — skip
        if (currentPrice >= vwap) {
            return null
        }
        
        // 2. Bollinger Band Width Volatility Gate:
        // Skip entry if BBW over the last 20 candles is too wide (> 3.5%)
        val bbPeriod = 20
        if (candles.size >= bbPeriod) {
            val last20 = candles.takeLast(bbPeriod)
            val closes = last20.map { it.close }
            val sma = closes.average()
            if (sma > 0.0) {
                val variance = closes.sumOf { Math.pow(it - sma, 2.0) }
                val sd = Math.sqrt(variance / bbPeriod)
                val bbw = (4.0 * sd) / sma
                if (bbw > 0.035) {
                    return null // Skip entry in highly volatile regimes
                }
            }
        }
        
        // 3. Mean reversion buy trigger: Price at/below 1.5σ band and starting to curve back up
        if (currentPrice <= lowerBand1_5 && latestCandle.close > latestCandle.open) {
            // Stop loss 2% below entry
            val stopLoss = currentPrice * 0.98
            // Fallback safety target of 4%
            val target = currentPrice * 1.04
            
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 2,
                strategyName = name,
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf(
                    "vwap" to vwap,
                    "lowerBand1_5" to lowerBand1_5,
                    "stdDev" to stdDev,
                    "stopLoss" to stopLoss,
                    "target" to target
                )
            )
        }
        
        return null
    }
}

/**
 * S6: OBV Divergence (On-Balance Volume)
 * Tracks cumulative volume to identify bullish divergence:
 * Price makes a lower low but OBV makes a higher low (accumulation).
 */
class ObvDivergence(
    val symbol: String,
    private val lookback: Int = 20
) : Strategy {
    override val name: String = "OBV Divergence"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < lookback + 10) return null
        
        // 1. Calculate OBV series
        val obvValues = ArrayList<Double>()
        var currentObv = 0.0
        obvValues.add(currentObv)
        
        for (i in 1 until candles.size) {
            val prev = candles[i - 1]
            val curr = candles[i]
            if (curr.close > prev.close) {
                currentObv += curr.volume
            } else if (curr.close < prev.close) {
                currentObv -= curr.volume
            }
            obvValues.add(currentObv)
        }
        
        // 2. Identify lows in Price and OBV over the lookback period
        // Find local troughs
        val priceLows = findLows(candles.map { it.close }, lookback)
        val obvLows = findLows(obvValues, lookback)
        
        if (priceLows.size >= 2 && obvLows.size >= 2) {
            val lastPriceLowIndex = priceLows.last()
            val prevPriceLowIndex = priceLows[priceLows.size - 2]
            
            val lastObvLowIndex = obvLows.last()
            val prevObvLowIndex = obvLows[obvLows.size - 2]
            
            // Check for Bullish Divergence:
            // Last price low is LOWER than previous price low,
            // BUT last OBV low is HIGHER than previous OBV low.
            val lastPriceLow = candles[lastPriceLowIndex].close
            val prevPriceLow = candles[prevPriceLowIndex].close
            
            val lastObvLow = obvValues[lastObvLowIndex]
            val prevObvLow = obvValues[prevObvLowIndex]
            
            if (lastPriceLow < prevPriceLow && lastObvLow > prevObvLow) {
                // Trigger buy on a minor bullish confirmation
                val latestCandle = candles.last()
                val currentPrice = currentTick?.price ?: latestCandle.close
                
                if (latestCandle.close > latestCandle.open) {
                    return Signal(
                        symbol = symbol,
                        direction = Direction.BUY,
                        score = 2,
                        strategyName = name,
                        triggerPrice = currentPrice,
                        timestamp = Instant.now(),
                        metadata = mapOf(
                            "lastPriceLow" to lastPriceLow,
                            "prevPriceLow" to prevPriceLow,
                            "lastObvLow" to lastObvLow,
                            "prevObvLow" to prevObvLow
                        )
                    )
                }
            }
        }
        
        return null
    }

    private fun findLows(values: List<Double>, lookback: Int): List<Int> {
        val lowIndices = ArrayList<Int>()
        val start = values.size - lookback
        
        for (i in start + 1 until values.size - 1) {
            if (values[i] < values[i - 1] && values[i] < values[i + 1]) {
                lowIndices.add(i)
            }
        }
        return lowIndices
    }
}

/**
 * VWAP Reclaim Strategy
 * 1. Stock dips below VWAP in the morning, consolidates (at least 3 candles with shrinking volume)
 * 2. Then a strong 5m candle closes back above VWAP with volume > 1.3x average
 * Entry: close of the reclaim candle
 * Stop: low of the candle that reclaimed VWAP
 * Target: VWAP + 1 ATR
 */
class VwapReclaim(
    val symbol: String
) : Strategy {
    override val name: String = "VWAP Reclaim"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        // Need at least 20 candles for average volume calculation
        if (candles.size < 20) return null

        val IST = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.now(IST)
        val todayStart = today.atTime(9, 15).atZone(IST).toInstant()
        val todayCandles = candles.filter { it.timestamp >= todayStart }

        // Need at least 3 candles below VWAP to consolidate + 1 candle to break out/reclaim
        if (todayCandles.size < 4) return null

        // Calculate daily VWAP series at each candle of the day
        var cumulativeTypicalPriceVolume = 0.0
        var cumulativeVolume = 0.0
        val vwapList = ArrayList<Double>()
        for (c in todayCandles) {
            val typicalPrice = (c.high + c.low + c.close) / 3.0
            cumulativeTypicalPriceVolume += typicalPrice * c.volume
            cumulativeVolume += c.volume
            val v = if (cumulativeVolume > 0.0) cumulativeTypicalPriceVolume / cumulativeVolume else typicalPrice
            vwapList.add(v)
        }

        val n = todayCandles.size - 1
        val triggerCandle = todayCandles[n]
        val triggerVwap = vwapList[n]

        // 1. Trigger candle must close above VWAP
        if (triggerCandle.close <= triggerVwap) return null

        // 2. The previous candle must have closed below or equal to VWAP
        if (todayCandles[n - 1].close > vwapList[n - 1]) return null

        // 3. Consolidates: the previous 3 candles must have closed below VWAP
        if (todayCandles[n - 2].close >= vwapList[n - 2] || todayCandles[n - 3].close >= vwapList[n - 3]) return null

        // 4. Shrinking volume during consolidation (v[n-1] <= v[n-2] and v[n-2] <= v[n-3])
        val vol1 = todayCandles[n - 1].volume
        val vol2 = todayCandles[n - 2].volume
        val vol3 = todayCandles[n - 3].volume
        val isShrinkingVolume = vol1 <= vol2 && vol2 <= vol3
        if (!isShrinkingVolume) return null

        // 5. Trigger candle volume must be > 1.3x of 20-candle average volume
        val avgVolume20 = candles.dropLast(1).takeLast(20).map { it.volume.toDouble() }.average()
        if (avgVolume20 <= 0.0 || triggerCandle.volume <= 1.3 * avgVolume20) return null

        // 6. Calculate ATR14 for target calculation
        val atr14 = calculateATR14(candles)
        val stopLoss = triggerCandle.low
        val target = triggerVwap + atr14

        // Prevent target being lower than entry
        val currentPrice = currentTick?.price ?: triggerCandle.close
        val finalTarget = if (target > currentPrice) target else currentPrice + (atr14 * 1.5)

        return Signal(
            symbol = symbol,
            direction = Direction.BUY,
            score = 3,
            strategyName = name,
            triggerPrice = currentPrice,
            timestamp = Instant.now(),
            metadata = mapOf(
                "vwap" to triggerVwap,
                "stopLoss" to stopLoss,
                "target" to finalTarget,
                "atr14" to atr14
            )
        )
    }

    private fun calculateATR14(candles: List<Candle>): Double {
        if (candles.size < 15) return 0.0
        val trList = ArrayList<Double>()
        for (i in 1 until candles.size) {
            val curr = candles[i]
            val prev = candles[i - 1]
            val hl = curr.high - curr.low
            val hcp = Math.abs(curr.high - prev.close)
            val lcp = Math.abs(curr.low - prev.close)
            trList.add(maxOf(hl, hcp, lcp))
        }
        var atr = trList.take(14).average()
        for (i in 14 until trList.size) {
            atr = (atr * 13 + trList[i]) / 14.0
        }
        return atr
    }
}

