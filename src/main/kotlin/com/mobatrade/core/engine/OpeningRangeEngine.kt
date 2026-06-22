package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Opening Range Breakout (ORB) Engine
 *
 * Strategy:
 * - Opening range = high and low of the first 3 candles of the day (9:15–9:30 AM, on 5m bars)
 * - A breakout is confirmed when the current candle CLOSES above the ORB high with volume surge
 * - Only fires between 9:30 AM – 10:30 AM IST (early ORB window)
 * - Fires at most once per stock per calendar day
 *
 * Stop = ORB low (clear invalidation level)
 * Target = entry + 2R (where R = entry - ORB low)
 */
object OpeningRangeEngine {

    data class OrbSignal(
        val symbol: String,
        val token: String,
        val entryPrice: Double,
        val stopLoss: Double,
        val target: Double,
        val riskPerShare: Double,
        val score: Int,
        val triggers: List<String>
    )

    // Tracks which stocks have already had an ORB signal fired today
    private val firedToday = ConcurrentHashMap<String, LocalDate>()

    @Volatile
    var enableLogging: Boolean = true

    private val IST = ZoneId.of("Asia/Kolkata")
    private val ORB_WINDOW_START = LocalTime.of(9, 30)
    private val ORB_WINDOW_END   = LocalTime.of(10, 30)

    /**
     * Given the full 5m candle history for a stock, attempts to detect an ORB breakout.
     * Returns an OrbSignal if all conditions are met, otherwise null.
     */
    fun detect(symbol: String, token: String, candles: List<Candle>): OrbSignal? {
        val lastCandle = candles.lastOrNull() ?: return null
        val candleTime = lastCandle.timestamp.atZone(IST)
        val now = candleTime.toLocalTime()
        val today = candleTime.toLocalDate()

        // 1. Only evaluate during the ORB entry window
        if (now.isBefore(ORB_WINDOW_START) || now.isAfter(ORB_WINDOW_END)) return null

        // 2. Don't fire twice for the same stock on the same day
        if (firedToday[symbol] == today) return null

        if (candles.size < 6) return null

        // 3. Extract today's candles only (from 9:15 AM IST)
        val todayStart = today.atTime(9, 15).atZone(IST).toInstant()
        val todayCandles = candles.filter { it.timestamp >= todayStart }

        // Need at least 3 candles for the range + 1 candle showing the breakout
        if (todayCandles.size < 4) return null

        // 4. Define the Opening Range from the first 3 x 5m candles (9:15, 9:20, 9:25)
        val orbCandles = todayCandles.take(3)
        val orbHigh = orbCandles.maxOf { it.high }
        val orbLow  = orbCandles.minOf { it.low }
        val orbRange = orbHigh - orbLow

        // Sanity check: skip flat/no-range opens (likely data issue)
        if (orbRange <= 0) return null

        // 5. Check if current candle (latest) closes ABOVE ORB high
        val current = candles.last()
        if (current.close <= orbHigh) return null

        // 6. Volume confirmation: compare to 20-period average of prior candles
        val priorCandles = candles.dropLast(1).takeLast(20)
        val avgVol20 = if (priorCandles.isNotEmpty()) priorCandles.map { it.volume.toDouble() }.average() else 0.0
        val volumeSurge = avgVol20 > 0 && current.volume > 1.5 * avgVol20

        // 7. Strong candle body: body >= 60% of the candle's range (not a wick)
        val body  = Math.abs(current.close - current.open)
        val range = current.high - current.low
        val strongBody = range > 0 && (body / range) >= 0.55

        // Score: base 2 for ORB breakout + bonus factors
        var score = 2
        val triggers = mutableListOf("ORB Breakout [close > ₹${String.format("%.2f", orbHigh)}] (+2)")

        if (volumeSurge) {
            score += 1
            triggers.add("Volume Surge [${current.volume} > 1.5x avg ${avgVol20.toLong()}] (+1)")
        }
        if (strongBody) {
            score += 1
            triggers.add("Strong Candle Body [${String.format("%.0f", (body / range) * 100)}%] (+1)")
        }

        // Require at least score 3 to fire (ORB breakout + at least 1 bonus)
        if (score < 3) {
            if (enableLogging) {
                println("  └─ [ORB] $symbol: ORB breakout detected but insufficient confirmation (score=$score/4). Skipping.")
            }
            return null
        }

        val entryPrice   = current.close
        val stopLoss     = orbLow
        val riskPerShare = entryPrice - stopLoss
        val target       = entryPrice + (2.0 * riskPerShare) // 2R target

        // Mark as fired for today
        firedToday[symbol] = today

        if (enableLogging) {
            println("🔥 [ORB] $symbol: BREAKOUT CONFIRMED! Entry=₹$entryPrice | ORB High=₹$orbHigh | Stop=₹$stopLoss | Target=₹$target | Score=$score")
        }

        return OrbSignal(
            symbol       = symbol,
            token        = token,
            entryPrice   = entryPrice,
            stopLoss     = stopLoss,
            target       = target,
            riskPerShare = riskPerShare,
            score        = score,
            triggers     = triggers
        )
    }

    /** Call at start of each day to clear yesterday's fired signals */
    fun clearStaleEntries(today: LocalDate = LocalDate.now(IST)) {
        firedToday.entries.removeIf { it.value != today }
    }
}
