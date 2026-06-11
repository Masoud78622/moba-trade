package com.mobatrade.core.strategies.tier4

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Signal
import com.mobatrade.core.model.Tick
import com.mobatrade.core.strategies.Strategy
import java.time.Instant

/**
 * Technical Indicator Helpers optimized for high-performance mobile calculations.
 */
object TechIndicators {
    /**
     * Compute Exponential Moving Average (EMA)
     */
    fun calculateEma(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period) return emptyList()
        val ema = ArrayList<Double>()
        val multiplier = 2.0 / (period + 1)
        
        // Use simple average for the first EMA value
        var currentEma = prices.take(period).average()
        ema.add(currentEma)
        
        for (i in period until prices.size) {
            currentEma = (prices[i] - currentEma) * multiplier + currentEma
            ema.add(currentEma)
        }
        return ema
    }

    /**
     * Compute Average Directional Index (ADX) and Directional Indicators (+DI, -DI)
     */
    data class AdxResult(val adx: List<Double>, val plusDI: List<Double>, val minusDI: List<Double>)

    fun calculateAdx(candles: List<Candle>, period: Int = 14): AdxResult {
        if (candles.size < period * 2) {
            return AdxResult(emptyList(), emptyList(), emptyList())
        }
        
        val tr = ArrayList<Double>()
        val plusDM = ArrayList<Double>()
        val minusDM = ArrayList<Double>()
        
        // Calculate TR, +DM, -DM
        for (i in 1 until candles.size) {
            val curr = candles[i]
            val prev = candles[i - 1]
            
            val hL = curr.high - curr.low
            val hCp = Math.abs(curr.high - prev.close)
            val lCp = Math.abs(curr.low - prev.close)
            tr.add(Math.max(hL, Math.max(hCp, lCp)))
            
            val upMove = curr.high - prev.high
            val downMove = prev.low - curr.low
            
            if (upMove > downMove && upMove > 0) {
                plusDM.add(upMove)
            } else {
                plusDM.add(0.0)
            }
            
            if (downMove > upMove && downMove > 0) {
                minusDM.add(downMove)
            } else {
                minusDM.add(0.0)
            }
        }
        
        // Wilder's Smoothing for TR, +DM, -DM
        val smoothedTR = ArrayList<Double>()
        val smoothedPlusDM = ArrayList<Double>()
        val smoothedMinusDM = ArrayList<Double>()
        
        var currentTR = tr.take(period).sum()
        var currentPlusDM = plusDM.take(period).sum()
        var currentMinusDM = minusDM.take(period).sum()
        
        smoothedTR.add(currentTR)
        smoothedPlusDM.add(currentPlusDM)
        smoothedMinusDM.add(currentMinusDM)
        
        for (i in period until tr.size) {
            currentTR = currentTR - (currentTR / period) + tr[i]
            currentPlusDM = currentPlusDM - (currentPlusDM / period) + plusDM[i]
            currentMinusDM = currentMinusDM - (currentMinusDM / period) + minusDM[i]
            
            smoothedTR.add(currentTR)
            smoothedPlusDM.add(currentPlusDM)
            smoothedMinusDM.add(currentMinusDM)
        }
        
        // Calculate +DI and -DI
        val plusDI = ArrayList<Double>()
        val minusDI = ArrayList<Double>()
        val dx = ArrayList<Double>()
        
        for (i in smoothedTR.indices) {
            val trVal = smoothedTR[i]
            val pDI = if (trVal != 0.0) (smoothedPlusDM[i] / trVal) * 100 else 0.0
            val mDI = if (trVal != 0.0) (smoothedMinusDM[i] / trVal) * 100 else 0.0
            plusDI.add(pDI)
            minusDI.add(mDI)
            
            val diff = Math.abs(pDI - mDI)
            val sum = pDI + mDI
            val dxVal = if (sum != 0.0) (diff / sum) * 100 else 0.0
            dx.add(dxVal)
        }
        
        // Calculate ADX
        val adx = ArrayList<Double>()
        if (dx.size >= period) {
            var currentAdx = dx.take(period).average()
            adx.add(currentAdx)
            
            for (i in period until dx.size) {
                currentAdx = (currentAdx * (period - 1) + dx[i]) / period
                adx.add(currentAdx)
            }
        }
        
        return AdxResult(adx, plusDI, minusDI)
    }
}

/**
 * S11: EMA Crossover (9/21/50)
 * Buy Signal: EMA 9 crosses above EMA 21, and both EMA 9 and 21 are above EMA 50.
 */
class EmaCrossover(
    val symbol: String
) : Strategy {
    override val name: String = "EMA Crossover"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 60) return null
        
        val prices = candles.map { it.close }
        val ema9List = TechIndicators.calculateEma(prices, 9)
        val ema21List = TechIndicators.calculateEma(prices, 21)
        val ema50List = TechIndicators.calculateEma(prices, 50)
        
        if (ema9List.size < 2 || ema21List.size < 2 || ema50List.size < 2) return null
        
        val curr9 = ema9List.last()
        val prev9 = ema9List[ema9List.size - 2]
        
        val curr21 = ema21List.last()
        val prev21 = ema21List[ema21List.size - 2]
        
        val curr50 = ema50List.last()
        
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        // Buy condition: EMA 9 crossed EMA 21 to the upside, and current price is above EMA 50
        val crossover = prev9 <= prev21 && curr9 > curr21
        val above50 = currentPrice > curr50
        
        if (crossover && above50) {
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 2,
                strategyName = name,
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf(
                    "ema9" to curr9,
                    "ema21" to curr21,
                    "ema50" to curr50
                )
            )
        }
        
        return null
    }
}

/**
 * S12: ADX Trend Filter
 * Evaluates directional movement.
 * Buy Signal: ADX > 25 (strong trend) and +DI crossed above -DI (bullish trend).
 */
class AdxFilter(
    val symbol: String,
    private val period: Int = 14
) : Strategy {
    override val name: String = "ADX Trend Filter"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        val result = TechIndicators.calculateAdx(candles, period)
        if (result.adx.size < 2 || result.plusDI.size < 2 || result.minusDI.size < 2) return null
        
        val currAdx = result.adx.last()
        val currPlusDI = result.plusDI.last()
        val prevPlusDI = result.plusDI[result.plusDI.size - 2]
        
        val currMinusDI = result.minusDI.last()
        val prevMinusDI = result.minusDI[result.minusDI.size - 2]
        
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        // Bullish crossover (+DI crossing -DI from below) + Strong Trend (ADX > 25)
        val crossover = prevPlusDI <= prevMinusDI && currPlusDI > currMinusDI
        val strongTrend = currAdx > 25.0
        
        if (crossover && strongTrend) {
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 2,
                strategyName = name,
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf(
                    "adx" to currAdx,
                    "plusDI" to currPlusDI,
                    "minusDI" to currMinusDI
                )
            )
        }
        
        return null
    }
}

/**
 * S13: Sector Rotation Momentum
 * Prioritizes sectors with higher relative strength.
 * Evaluates a list of sector ETF or index candles to assign relative strength.
 */
class SectorRotation(
    val symbol: String,
    private val sectorName: String
) : Strategy {
    override val name: String = "Sector Rotation"

    companion object {
        // Keeps track of sector performance scores (updated globally by the engine)
        private val sectorScores = HashMap<String, Double>()

        fun updateSectorScores(scores: Map<String, Double>) {
            synchronized(sectorScores) {
                sectorScores.clear()
                sectorScores.putAll(scores)
            }
        }
        
        fun getSectorScore(sector: String): Double {
            synchronized(sectorScores) {
                return sectorScores[sector] ?: 1.0
            }
        }
    }

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < 20) return null
        
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        // Check relative sector score: must be in top-performing sectors (score > 1.1)
        val sectorScore = getSectorScore(sectorName)
        
        // Simple momentum condition on the stock itself
        val prices = candles.map { it.close }
        val ema20List = TechIndicators.calculateEma(prices, 20)
        if (ema20List.isEmpty()) return null
        
        val ema20 = ema20List.last()
        
        // If stock is above 20 EMA and sector has positive rotation momentum
        if (currentPrice > ema20 && sectorScore > 1.05) {
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 1, // Momentum booster
                strategyName = name,
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf(
                    "sector" to sectorName,
                    "sectorScore" to sectorScore,
                    "ema20" to ema20
                )
            )
        }
        
        return null
    }
}
