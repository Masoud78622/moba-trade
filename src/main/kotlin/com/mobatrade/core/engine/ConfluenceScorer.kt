package com.mobatrade.core.engine

import com.mobatrade.core.halal.ShariahFilter
import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.model.Signal
import com.mobatrade.core.model.Tick
import com.mobatrade.core.strategies.Strategy
import com.mobatrade.core.strategies.tier1.OpeningRangeBreakout
import com.mobatrade.core.strategies.tier1.DarvasBox
import com.mobatrade.core.strategies.tier1.SupportResistanceFlip
import com.mobatrade.core.strategies.tier2.VolumeProfile
import com.mobatrade.core.strategies.tier2.VwapDevBands
import com.mobatrade.core.strategies.tier2.ObvDivergence
import com.mobatrade.core.strategies.tier3.OrderBlocks
import com.mobatrade.core.strategies.tier3.BreakOfStructure
import com.mobatrade.core.strategies.tier3.FairValueGap
import com.mobatrade.core.strategies.tier3.LiquiditySweep
import com.mobatrade.core.strategies.tier4.EmaCrossover
import com.mobatrade.core.strategies.tier4.AdxFilter
import com.mobatrade.core.strategies.tier4.SectorRotation
import com.mobatrade.core.strategies.tier5.NewsSentiment
import com.mobatrade.core.strategies.tier5.RegimeDetector
import com.mobatrade.core.strategies.tier5.PatternRecognition
import java.time.Instant

class ConfluenceScorer(
    val symbol: String,
    val sectorName: String
) {
    private val regimeDetector = RegimeDetector()
    private val adxFilter = AdxFilter(symbol)
    private val sectorRotation = SectorRotation(symbol, sectorName)
    
    // Independent Factor Analyzers
    private val darvasBox = DarvasBox(symbol)
    private val breakOfStructure = BreakOfStructure(symbol)
    private val vwapDevBands = VwapDevBands(symbol)
    private val patternRecognition = PatternRecognition(symbol)

    data class ScoredTrade(
        val symbol: String,
        val totalScore: Int,
        val recommendedDirection: Direction,
        val marketRegime: MarketRegime,
        val triggers: List<String>,
        val isShariahCompliant: Boolean,
        val isSwingEligible: Boolean,
        val atr14: Double
    )

    /**
     * Scores the asset from 0 to 5 by evaluating clean strategy signals.
     * Enforces trend and strength gates (EMA50 and ADX > 25).
     * Enforces an absolute Shariah filter.
     */
    fun scoreTrade(candles: List<Candle>, currentTick: Tick? = null): ScoredTrade {
        // 1. CRITICAL: Shariah Filter Check. Non-compliant assets get scored 0 immediately.
        val isCompliant = ShariahFilter.isCompliantSymbol(symbol)
        if (!isCompliant) {
            return ScoredTrade(
                symbol = symbol,
                totalScore = 0,
                recommendedDirection = Direction.HOLD,
                marketRegime = MarketRegime.RANGING,
                triggers = listOf("NON_SHARIAH_COMPLIANT"),
                isShariahCompliant = false,
                isSwingEligible = false,
                atr14 = 0.0
            )
        }

        // 2. Classify Market Regime
        val regime = regimeDetector.detectRegime(candles)
        
        // Strictly bearish trend is unsafe for long-only setups.
        if (regime == MarketRegime.TRENDING_BEARISH) {
            return ScoredTrade(
                symbol = symbol,
                totalScore = 0,
                recommendedDirection = Direction.HOLD,
                marketRegime = regime,
                triggers = listOf("REGIME_BEARISH_NO_LONG_ENTRY"),
                isShariahCompliant = true,
                isSwingEligible = false,
                atr14 = 0.0
            )
        }

        // Calculate ATR for the scored trade
        val atr14 = if (candles.size >= 14) {
            val last15 = candles.takeLast(15)
            var trSum = 0.0
            for (i in 1 until last15.size) {
                val current = last15[i]
                val prev = last15[i - 1]
                val hl = current.high - current.low
                val hc = Math.abs(current.high - prev.close)
                val lc = Math.abs(current.low - prev.close)
                trSum += maxOf(hl, hc, lc)
            }
            trSum / 14.0
        } else 0.0

        // --- MANDATORY GATES ---
        
        // Gate A: Trend Gate (Price close must be above EMA 50 of 15m candles)
        val closePrices = candles.map { it.close }
        val ema50 = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(closePrices, 50)
        val isTrendGatePassed = ema50.isNotEmpty() && candles.last().close > ema50.last()
        if (!isTrendGatePassed) {
            return ScoredTrade(
                symbol = symbol,
                totalScore = 0,
                recommendedDirection = Direction.HOLD,
                marketRegime = regime,
                triggers = listOf("FAILED_MANDATORY_TREND_GATE_EMA50"),
                isShariahCompliant = true,
                isSwingEligible = false,
                atr14 = atr14
            )
        }

        var totalScore = 0.0
        val triggers = ArrayList<String>()
        var structuralTriggerFound = false

        // --- SCORED SIGNALS ---

        // 1. Trend Strength Factor: ADX > 25
        val adxSignal = adxFilter.evaluate(candles, currentTick)
        if (adxSignal != null && adxSignal.direction == Direction.BUY) {
            totalScore += 1.0
            triggers.add("Trend Strength [ADX>25] (+1.0)")
        }

        // 2. Structure Factor: Darvas Box OR Break of Structure
        val darvasSignal = darvasBox.evaluate(candles, currentTick)
        val bosSignal = breakOfStructure.evaluate(candles, currentTick)
        
        if ((darvasSignal != null && darvasSignal.direction == Direction.BUY) || 
            (bosSignal != null && bosSignal.direction == Direction.BUY)) {
            totalScore += 1.0
            val triggerName = if (darvasSignal != null && darvasSignal.direction == Direction.BUY) "Darvas" else "BOS"
            triggers.add("Structural Breakout [$triggerName] (+1.0)")
            structuralTriggerFound = true
        }

        // 2. Pattern Factor: Bullish Flag OR Double Bottom
        val patternSignal = patternRecognition.evaluate(candles, currentTick)
        if (patternSignal != null && patternSignal.direction == Direction.BUY) {
            totalScore += 1.0
            triggers.add("${patternSignal.strategyName} (+1.0)")
        }

        // 3. Momentum Factor: VWAP Deviation Bands
        val vwapSignal = vwapDevBands.evaluate(candles, currentTick)
        if (vwapSignal != null && vwapSignal.direction == Direction.BUY) {
            totalScore += 1.0
            triggers.add("${vwapDevBands.name} (+1.0)")
        }

        // 4. Volume Breakout Signal (1 point)
        val lastCandle = candles.last()
        val avgVolume20 = if (candles.size >= 2) candles.dropLast(1).takeLast(20).map { it.volume.toDouble() }.average() else 0.0
        val isVolumeBreakout = avgVolume20 > 0 && lastCandle.volume > 1.5 * avgVolume20
        if (isVolumeBreakout) {
            totalScore += 1.0
            triggers.add("Volume Breakout (+1.0)")
        }

        // 5. Sector Rotation Bonus (+0.5 point)
        val sectorSignal = sectorRotation.evaluate(candles, currentTick)
        if (sectorSignal != null && sectorSignal.direction == Direction.BUY) {
            totalScore += 0.5
            triggers.add("Sector Outperformance (+0.5)")
        }

        // Final Score rounding and evaluation (Capped at 5)
        val roundedScore = Math.round(totalScore).toInt().coerceIn(0, 5)
        // BUY if score >= 3
        val finalDirection = if (roundedScore >= 3) Direction.BUY else Direction.HOLD
        
        // Swing Eligibility: High conviction (score >= 4) + Structural breakout + trending bullish
        val swingEligible = structuralTriggerFound && roundedScore >= 4 && regime == MarketRegime.TRENDING_BULLISH

        return ScoredTrade(
            symbol = symbol,
            totalScore = roundedScore,
            recommendedDirection = finalDirection,
            marketRegime = regime,
            triggers = triggers,
            isShariahCompliant = true,
            isSwingEligible = swingEligible,
            atr14 = atr14
        )
    }
}
