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
    private val strategies = ArrayList<Strategy>()
    private val regimeDetector = RegimeDetector()

    init {
        // Initialize all 16 strategy components for this symbol
        strategies.add(OpeningRangeBreakout(symbol))
        strategies.add(DarvasBox(symbol))
        strategies.add(SupportResistanceFlip(symbol))
        strategies.add(VolumeProfile(symbol))
        strategies.add(VwapDevBands(symbol))
        strategies.add(ObvDivergence(symbol))
        strategies.add(OrderBlocks(symbol))
        strategies.add(BreakOfStructure(symbol))
        strategies.add(FairValueGap(symbol))
        strategies.add(LiquiditySweep(symbol))
        strategies.add(EmaCrossover(symbol))
        strategies.add(AdxFilter(symbol))
        strategies.add(SectorRotation(symbol, sectorName))
        strategies.add(NewsSentiment(symbol))
        strategies.add(PatternRecognition(symbol))
    }

    data class ScoredTrade(
        val symbol: String,
        val totalScore: Int,
        val recommendedDirection: Direction,
        val marketRegime: MarketRegime,
        val triggers: List<String>,
        val isShariahCompliant: Boolean,
        val isSwingEligible: Boolean
    )

    /**
     * Scores the asset from 0 to 10 by evaluating all active strategies.
     * Integrates Market Regime filter to adjust strategy weights.
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
                isSwingEligible = false
            )
        }

        // 2. Classify Market Regime
        val regime = regimeDetector.detectRegime(candles)
        
        // Volatile and bearish regimes are unsafe for standard long-only setups
        if (regime == MarketRegime.TRENDING_BEARISH || regime == MarketRegime.VOLATILE) {
            return ScoredTrade(
                symbol = symbol,
                totalScore = 0,
                recommendedDirection = Direction.HOLD,
                marketRegime = regime,
                triggers = listOf("REGIME_UNSAFE_FOR_BUYING"),
                isShariahCompliant = true,
                isSwingEligible = false
            )
        }

        var totalScore = 0
        val triggers = ArrayList<String>()
        var structuralTriggerFound = false

        // 3. Evaluate each strategy and sum weighted scores
        for (strategy in strategies) {
            val signal = strategy.evaluate(candles, currentTick)
            if (signal != null && signal.direction == Direction.BUY) {
                var weight = signal.score
                
                // Track structural swing indicators
                if (strategy is BreakOfStructure || strategy is DarvasBox || strategy is OrderBlocks || strategy is SectorRotation) {
                    structuralTriggerFound = true
                }
                
                // Regime-Based Score Adaptation
                when (regime) {
                    MarketRegime.TRENDING_BULLISH -> {
                        // Double weight for trend-following and momentum strategies
                        if (strategy is OpeningRangeBreakout || strategy is DarvasBox || 
                            strategy is EmaCrossover || strategy is BreakOfStructure || 
                            strategy is AdxFilter) {
                            weight += 1
                        }
                    }
                    MarketRegime.RANGING -> {
                        // Double weight for mean-reversion, volume, and supply/demand zones
                        if (strategy is VolumeProfile || strategy is VwapDevBands || 
                            strategy is OrderBlocks || strategy is LiquiditySweep || 
                            strategy is SupportResistanceFlip || strategy is FairValueGap) {
                            weight += 1
                        }
                    }
                    else -> {}
                }
                
                // Add dynamically learned EOD bonus
                val learnedBonus = LearnedWeights.getBonus(strategy.name)
                weight += learnedBonus
                
                totalScore += weight
                val triggerString = if (learnedBonus > 0) "${strategy.name} (+$weight) [AI+${learnedBonus}]" else "${strategy.name} (+$weight)"
                triggers.add(triggerString)
            }
        }

        // Cap score at 10
        val finalScore = totalScore.coerceIn(0, 10)
        val finalDirection = if (finalScore >= 4) Direction.BUY else Direction.HOLD
        
        // Swing Eligibility: High conviction + Structural support
        val swingEligible = structuralTriggerFound && finalScore >= 8 && regime == MarketRegime.TRENDING_BULLISH

        return ScoredTrade(
            symbol = symbol,
            totalScore = finalScore,
            recommendedDirection = finalDirection,
            marketRegime = regime,
            triggers = triggers,
            isShariahCompliant = true,
            isSwingEligible = swingEligible
        )
    }
}
