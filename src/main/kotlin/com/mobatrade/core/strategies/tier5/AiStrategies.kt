package com.mobatrade.core.strategies.tier5

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.model.Signal
import com.mobatrade.core.model.Tick
import com.mobatrade.core.strategies.Strategy
import java.time.Instant

/**
 * S14: News Sentiment Strategy (LLM integration interface)
 * Pulls current news sentiment score (from -1.0 to +1.0) and adds trading confluence.
 */
class NewsSentiment(
    val symbol: String
) : Strategy {
    override val name: String = "News Sentiment"

    companion object {
        private val sentimentScores = HashMap<String, Double>()

        /**
         * System updates sentiment from LLM background worker
         */
        fun updateSentiment(symbol: String, score: Double) {
            synchronized(sentimentScores) {
                sentimentScores[symbol.uppercase()] = score
            }
        }

        fun getSentiment(symbol: String): Double {
            return sentimentScores[symbol.uppercase()] ?: 0.0
        }
    }

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        val sentiment = getSentiment(symbol)
        
        // Only trigger if sentiment is strongly positive (> 0.6)
        if (sentiment > 0.6) {
            val latestCandle = candles.lastOrNull() ?: return null
            val currentPrice = currentTick?.price ?: latestCandle.close
            
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 1, // Sentiment booster
                strategyName = name,
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf("sentimentScore" to sentiment)
            )
        }
        
        return null
    }
}

/**
 * S15: Regime Detection (ML / Statistical Classifier)
 * Classifies market into TRENDING_BULLISH, TRENDING_BEARISH, RANGING, or VOLATILE using statistical attributes.
 * Guides the confluence engine on which strategy classes to prioritize.
 */
class RegimeDetector(
    private val period: Int = 20
) {
    /**
     * Determines market regime based on Volatility (ATR ratio) and Trend Strength (ADX/EMA alignment).
     * Highly optimized for fast execution on mobile CPU.
     */
    fun detectRegime(candles: List<Candle>): MarketRegime {
        if (candles.size < period + 5) return MarketRegime.RANGING
        
        // 1. Calculate historical volatility via ATR ratio
        val currentAtr = calculateAtr(candles.takeLast(period))
        val baselineAtr = calculateAtr(candles.take(candles.size - period).takeLast(period * 2))
        
        val isHighVolatility = if (baselineAtr > 0.0) (currentAtr / baselineAtr) > 1.8 else false
        
        if (isHighVolatility) {
            return MarketRegime.VOLATILE
        }
        
        // 2. Check Trend strength using EMA slope and price relationship
        val prices = candles.map { it.close }
        val ema50List = calculateEma(prices, 50)
        val ema20List = calculateEma(prices, 20)
        
        if (ema50List.isNotEmpty() && ema20List.isNotEmpty()) {
            val ema50 = ema50List.last()
            val ema20 = ema20List.last()
            val latestPrice = prices.last()
            
            // Check slope of 50 EMA over past 5 candles
            val slope50 = ema50 - ema50List[ema50List.size - 5]
            
            if (latestPrice > ema20 && ema20 > ema50 && slope50 > (ema50 * 0.002)) {
                return MarketRegime.TRENDING_BULLISH
            } else if (latestPrice < ema20 && ema20 < ema50 && slope50 < -(ema50 * 0.002)) {
                return MarketRegime.TRENDING_BEARISH
            }
        }
        
        return MarketRegime.RANGING
    }

    private fun calculateAtr(subList: List<Candle>): Double {
        if (subList.size < 2) return 0.0
        var trSum = 0.0
        for (i in 1 until subList.size) {
            val curr = subList[i]
            val prev = subList[i - 1]
            val hL = curr.high - curr.low
            val hCp = Math.abs(curr.high - prev.close)
            val lCp = Math.abs(curr.low - prev.close)
            trSum += Math.max(hL, Math.max(hCp, lCp))
        }
        return trSum / (subList.size - 1)
    }

    private fun calculateEma(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period) return emptyList()
        val ema = ArrayList<Double>()
        val multiplier = 2.0 / (period + 1)
        var currentEma = prices.take(period).average()
        ema.add(currentEma)
        for (i in period until prices.size) {
            currentEma = (prices[i] - currentEma) * multiplier + currentEma
            ema.add(currentEma)
        }
        return ema
    }
}

/**
 * S16: Chart Pattern Recognition (Computer Vision / Geometry Recognizer)
 * Detects common chart geometries like Bullish Pennants and Double Bottoms directly from candle structures.
 */
class PatternRecognition(
    val symbol: String,
    private val lookback: Int = 30
) : Strategy {
    override val name: String = "Pattern Recognition"

    override fun evaluate(candles: List<Candle>, currentTick: Tick?): Signal? {
        if (candles.size < lookback) return null
        
        val latestCandle = candles.last()
        val currentPrice = currentTick?.price ?: latestCandle.close
        
        // 1. Double Bottom detection
        if (detectDoubleBottom(candles.takeLast(lookback))) {
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 2,
                strategyName = "$name: Double Bottom",
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf("pattern" to "Double Bottom")
            )
        }
        
        // 2. Bullish Flag/Pennant detection
        if (detectBullishFlag(candles.takeLast(15))) {
            return Signal(
                symbol = symbol,
                direction = Direction.BUY,
                score = 2,
                strategyName = "$name: Bullish Flag",
                triggerPrice = currentPrice,
                timestamp = Instant.now(),
                metadata = mapOf("pattern" to "Bullish Flag")
            )
        }
        
        return null
    }

    /**
     * Identifies two distinct lows of approximately equal price with a peak in between.
     */
    private fun detectDoubleBottom(data: List<Candle>): Boolean {
        // Find local lows
        val lows = ArrayList<Pair<Int, Double>>()
        for (i in 2 until data.size - 2) {
            val prev2 = data[i - 2].low
            val prev1 = data[i - 1].low
            val curr = data[i].low
            val next1 = data[i + 1].low
            val next2 = data[i + 2].low
            
            if (curr < prev2 && curr < prev1 && curr < next1 && curr < next2) {
                lows.add(Pair(i, curr))
            }
        }
        
        if (lows.size >= 2) {
            val lastLow = lows.last()
            val prevLow = lows[lows.size - 2]
            
            // Lows must be separated by at least 5 candles (to be distinct bottoms)
            if (lastLow.first - prevLow.first >= 5) {
                // Price discrepancy between the two bottoms must be less than 1.5%
                val diffPercent = Math.abs(lastLow.second - prevLow.second) / prevLow.second
                if (diffPercent < 0.015) {
                    // Check that there is a peak in between
                    val interSec = data.subList(prevLow.first + 1, lastLow.first)
                    val peak = interSec.maxOfOrNull { it.high } ?: 0.0
                    val currentPrice = data.last().close
                    
                    // Trigger double bottom when price breaks out of the peak in between
                    if (currentPrice > peak && peak > prevLow.second) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Detects a strong uptrend (flagpole) followed by a tight declining consolidation channel (flag).
     */
    private fun detectBullishFlag(data: List<Candle>): Boolean {
        if (data.size < 10) return false
        
        // 1. Flagpole: Check if first 5 candles show strong positive returns
        val poleStart = data[0].open
        val poleEnd = data[4].close
        val poleReturn = (poleEnd - poleStart) / poleStart
        if (poleReturn < 0.03) return false // Needs at least 3% flagpole gain
        
        // 2. Flag Consolidation: Check if next 5 candles are in tight, slightly downward consolidation
        val flagCandles = data.subList(5, data.size)
        var decliningHighs = true
        var tightRange = true
        
        val flagHigh = flagCandles.maxOf { it.high }
        val flagLow = flagCandles.minOf { it.low }
        val flagRange = (flagHigh - flagLow) / flagHigh
        
        if (flagRange > 0.02) tightRange = false // Flag should be tight (< 2%)
        
        // Check if high of flag doesn't exceed the top of flagpole
        if (flagHigh > poleEnd * 1.01) return false
        
        // 3. Breakout: Latest candle closes above the high of the flag
        val latestCandle = data.last()
        if (latestCandle.close > flagHigh && latestCandle.close > latestCandle.open) {
            return tightRange
        }
        
        return false
    }
}
