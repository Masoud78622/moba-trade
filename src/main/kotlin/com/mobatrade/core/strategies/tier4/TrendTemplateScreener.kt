package com.mobatrade.core.strategies.tier4

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.strategies.tier5.RegimeDetector
import java.time.LocalDate
import java.time.ZoneId

object TrendTemplateScreener {

    private val IST = ZoneId.of("Asia/Kolkata")
    private val regimeDetector = RegimeDetector()

    data class ScreenResult(
        val isTriggered: Boolean,
        val price: Double,
        val rsScore: Double,
        val niftyRegime: MarketRegime,
        val details: String,
        val vcpWidth: Double = 0.0
    )

    fun screen(
        symbol: String,
        targetDate: LocalDate,
        stockCandles: List<Candle>,
        niftyCandles: List<Candle>,
        minRsScore: Double = 15.0,
        rsPercentile: Double? = null,
        requireVcp: Boolean = false,
        maxVcpPriceRangePct: Double = 3.0,
        minVcpVolumeContractionPct: Double = 15.0,
        requirePullback: Boolean = true,
        requireNiftyStage2: Boolean = false,
        requireLiquiditySweep: Boolean = false
    ): ScreenResult {
        // 1. Align Nifty and Stock candles up to the target date
        val stockIdx = stockCandles.indexOfLast { it.timestamp.atZone(IST).toLocalDate() == targetDate }
        val niftyIdx = niftyCandles.indexOfLast { it.timestamp.atZone(IST).toLocalDate() == targetDate }

        if (stockIdx == -1 || niftyIdx == -1) {
            return ScreenResult(false, 0.0, 0.0, MarketRegime.RANGING, "Target date $targetDate not found in candle data.")
        }

        // We need enough historical days to calculate:
        // - 200 SMA (requires 200 days) + 22 days trend = 222 days of stock history
        // - 120 days of stock/Nifty history for 6-month returns
        if (stockIdx < 222 || niftyIdx < 120) {
            return ScreenResult(false, 0.0, 0.0, MarketRegime.RANGING, "Insufficient daily candle history ($stockIdx stock, $niftyIdx Nifty).")
        }

        val currentPrice = stockCandles[stockIdx].close
        val stockPrices = stockCandles.subList(0, stockIdx + 1).map { it.close }
        val stockLows = stockCandles.subList(0, stockIdx + 1).map { it.low }
        val stockHighs = stockCandles.subList(0, stockIdx + 1).map { it.high }

        val niftyPrices = niftyCandles.subList(0, niftyIdx + 1).map { it.close }

        // 2. Compute Moving Averages
        val sma50 = calculateSma(stockPrices, 50)
        val sma150 = calculateSma(stockPrices, 150)
        val sma200 = calculateSma(stockPrices, 200)

        val ema10 = calculateEma(stockPrices, 10)
        val ema21 = calculateEma(stockPrices, 21)

        val cur50 = sma50.last()
        val cur150 = sma150.last()
        val cur200 = sma200.last()
        
        // 200 SMA today vs 22 trading days ago (~1 month)
        val prev200 = sma200[sma200.size - 23]

        // 3. Compute 52-Week High and Low (approx. 250 trading days)
        val last250 = stockCandles.subList(maxOf(0, stockIdx - 250), stockIdx + 1)
        val low52 = last250.minOf { it.low }
        val high52 = last250.maxOf { it.high }

        // 4. Compute Custom RS Outperformance vs Nifty
        val stockReturn3m = ((currentPrice - stockPrices[stockIdx - 60]) / stockPrices[stockIdx - 60]) * 100.0
        val niftyReturn3m = ((niftyPrices.last() - niftyPrices[niftyIdx - 60]) / niftyPrices[niftyIdx - 60]) * 100.0
        val outperformance3m = stockReturn3m - niftyReturn3m

        val stockReturn6m = ((currentPrice - stockPrices[stockIdx - 120]) / stockPrices[stockIdx - 120]) * 100.0
        val niftyReturn6m = ((niftyPrices.last() - niftyPrices[niftyIdx - 120]) / niftyPrices[niftyIdx - 120]) * 100.0
        val outperformance6m = stockReturn6m - niftyReturn6m

        val rsScore = (outperformance3m + outperformance6m) / 2.0

        // 5. Evaluate the 8 Trend Template Conditions
        val c1 = currentPrice > cur150 && currentPrice > cur200
        val c2 = cur150 > cur200
        val c3 = cur200 > prev200
        val c4 = cur50 > cur150 && cur50 > cur200
        val c5 = currentPrice > cur50
        val c6 = currentPrice >= low52 * 1.30
        val c7 = currentPrice >= high52 * 0.75
        val c8 = if (rsPercentile != null) rsPercentile >= 85.0 else rsScore >= minRsScore

        // VCP Check
        var vcpMet = true
        var vcpDetails = ""
        var consRangePct = 0.0
        if (requireVcp && stockIdx >= 20) {
            val consCandles = stockCandles.subList(stockIdx - 5, stockIdx)
            val consHigh = consCandles.maxOf { it.high }
            val consLow = consCandles.minOf { it.low }
            consRangePct = ((consHigh - consLow) / consLow) * 100.0

            val consVol = consCandles.map { it.volume.toDouble() }.average()
            val baseVol = stockCandles.subList(stockIdx - 20, stockIdx - 5).map { it.volume.toDouble() }.average()
            val volContraction = if (baseVol > 0.0) ((baseVol - consVol) / baseVol) * 100.0 else 0.0

            val priceTight = consRangePct <= maxVcpPriceRangePct
            val volDry = volContraction >= minVcpVolumeContractionPct

            vcpMet = priceTight && volDry
            vcpDetails = "Range=${String.format("%.1f%%", consRangePct)} (max ${maxVcpPriceRangePct}%) | VolDry=${String.format("%.1f%%", volContraction)} (min ${minVcpVolumeContractionPct}%)"
        }

        val trendTemplateMet = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && vcpMet

        if (!trendTemplateMet) {
            val failed = mutableListOf<String>()
            if (!c1) failed.add("Price > MA150/200")
            if (!c2) failed.add("MA150 > MA200")
            if (!c3) failed.add("MA200 trending up")
            if (!c4) failed.add("MA50 > MA150/200")
            if (!c5) failed.add("Price > MA50")
            if (!c6) failed.add("Price >= 30% above 52w low")
            if (!c7) failed.add("Price within 25% of 52w high")
            if (!c8) failed.add(if (rsPercentile != null) "RS Percentile ($rsPercentile) < 85%" else "RS Score ($rsScore) < $minRsScore")
            if (!vcpMet) failed.add("VCP failed: $vcpDetails")
            return ScreenResult(false, currentPrice, rsScore, MarketRegime.RANGING, "Failed Trend Template: ${failed.joinToString(", ")}")
        }

        // 6. Check Pullback vs Breakout Entry vs Liquidity Sweep
        var triggerReason = ""
        if (requireLiquiditySweep) {
            val yesterdayLow = stockLows[stockIdx - 1]
            val todayLow = stockLows[stockIdx]
            val isGreen = currentPrice > stockCandles[stockIdx].open
            val isSweep = todayLow < yesterdayLow
            val isRecovery = currentPrice > yesterdayLow

            if (!isSweep || !isRecovery || !isGreen) {
                return ScreenResult(false, currentPrice, rsScore, MarketRegime.RANGING, "Trend Template/VCP Met but no daily liquidity sweep of yesterday's low ($yesterdayLow).")
            }
            triggerReason = "Daily Liquidity Sweep of yesterday's low ($yesterdayLow) with Recovery"
        } else if (requirePullback) {
            val curEma10 = ema10.last()
            val curEma21 = ema21.last()

            val hadPullback10 = (1..4).any { offset ->
                val t = stockIdx - offset
                val emaIdx = ema10.size - 1 - offset
                emaIdx >= 0 && stockLows[t] <= ema10[emaIdx] * 1.005
            }
            val hadPullback21 = (1..4).any { offset ->
                val t = stockIdx - offset
                val emaIdx = ema21.size - 1 - offset
                emaIdx >= 0 && stockLows[t] <= ema21[emaIdx] * 1.005
            }

            // Today must be a green candle closing above the EMAs, bouncing from them
            val isBounce10 = hadPullback10 && currentPrice > stockCandles[stockIdx].open && currentPrice > curEma10
            val isBounce21 = hadPullback21 && currentPrice > stockCandles[stockIdx].open && currentPrice > curEma21
            val isPullbackTriggered = isBounce10 || isBounce21

            if (!isPullbackTriggered) {
                return ScreenResult(false, currentPrice, rsScore, MarketRegime.RANGING, "Trend Template Met but no Pullback/Bounce setup detected.")
            }
            triggerReason = if (isBounce10 && isBounce21) "Bounce 10 & 21 EMA" else if (isBounce10) "Bounce 10 EMA" else "Bounce 21 EMA"
        } else {
            // Breakout entry: Green candle + volume expansion + closing above 15-day pivot high
            val isGreen = currentPrice > stockCandles[stockIdx].open
            val lastCandle = stockCandles[stockIdx]
            val avgVolume50 = if (stockIdx >= 50) stockCandles.subList(stockIdx - 50, stockIdx).map { it.volume.toDouble() }.average() else 0.0
            val isVolumeExpansion = avgVolume50 > 0 && lastCandle.volume > 1.3 * avgVolume50
            val pivotHigh = stockHighs.subList(maxOf(0, stockIdx - 15), stockIdx).maxOrNull() ?: 0.0
            val isBreakout = currentPrice > pivotHigh

            if (!isGreen || !isVolumeExpansion || !isBreakout) {
                return ScreenResult(false, currentPrice, rsScore, MarketRegime.RANGING, "Trend Template/VCP Met but no breakout above pivot ($pivotHigh) with volume expansion.")
            }
            triggerReason = "Breakout above pivot ($pivotHigh) with Volume Expansion"
        }

        // 7. Gate on Nifty Market Regime (must NOT be Trending Bearish)
        val niftyRegime = regimeDetector.detectRegime(niftyCandles.subList(0, niftyIdx + 1))
        if (niftyRegime == MarketRegime.TRENDING_BEARISH) {
            return ScreenResult(false, currentPrice, rsScore, niftyRegime, "Skip entry: Nifty is in a TRENDING_BEARISH regime.")
        }

        // Optional Nifty Stage-2 Uptrend Filter
        if (requireNiftyStage2) {
            val niftySma50 = calculateSma(niftyPrices, 50)
            val niftySma200 = calculateSma(niftyPrices, 200)
            if (niftySma50.isNotEmpty() && niftySma200.isNotEmpty()) {
                val curNifty50 = niftySma50.last()
                val curNifty200 = niftySma200.last()
                val isNiftyStage2 = niftyPrices.last() > curNifty50 && curNifty50 > curNifty200
                if (!isNiftyStage2) {
                    return ScreenResult(false, currentPrice, rsScore, niftyRegime, "Skip entry: Nifty is NOT in a Stage-2 Uptrend (Close > Nifty50 > Nifty200).")
                }
            } else {
                return ScreenResult(false, currentPrice, rsScore, niftyRegime, "Skip entry: Insufficient Nifty history for Stage-2 check.")
            }
        }

        return ScreenResult(true, currentPrice, rsScore, niftyRegime, "SETUP TRIGGERED! $triggerReason. VCP: $vcpDetails", consRangePct)
    }

    fun calculateSma(prices: List<Double>, period: Int): List<Double> {
        if (prices.size < period) return emptyList()
        val sma = ArrayList<Double>()
        var sum = prices.take(period).sum()
        sma.add(sum / period)
        for (i in period until prices.size) {
            sum += prices[i] - prices[i - period]
            sma.add(sum / period)
        }
        return sma
    }

    fun calculateEma(prices: List<Double>, period: Int): List<Double> {
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
