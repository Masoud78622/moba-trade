package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle

data class MfeMaeResult(
    val symbol: String,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val mfeAmount: Double,        // Peak favorable gain in rupees/points
    val mfePct: Double,           // Peak favorable gain %
    val maeAmount: Double,        // Peak adverse drawdown in rupees/points
    val maePct: Double,           // Peak adverse drawdown %
    val exitReason: String,       // HIT_TARGET, HIT_STOP, EOD_CLOSE
    val exitPrice: Double,        // Actual price at exit
    val realizedPLPct: Double,    // Final P&L %
    val rMultiple: Double         // Realized R-Multiple (e.g. +2.0, -1.0, +0.5)
)

object MfeMaeAnalyzer {
    /**
     * Evaluates Maximum Favorable Excursion (MFE) and Maximum Adverse Excursion (MAE)
     * candle-by-candle for a long position post-entry.
     */
    fun evaluateLongTrade(
        symbol: String,
        entryPrice: Double,
        stopPrice: Double,
        targetPrice: Double,
        futureCandles: List<Candle>
    ): MfeMaeResult {
        if (futureCandles.isEmpty() || entryPrice <= 0.0) {
            return MfeMaeResult(
                symbol = symbol,
                entryPrice = entryPrice,
                stopPrice = stopPrice,
                targetPrice = targetPrice,
                mfeAmount = 0.0,
                mfePct = 0.0,
                maeAmount = 0.0,
                maePct = 0.0,
                exitReason = "NO_DATA",
                exitPrice = entryPrice,
                realizedPLPct = 0.0,
                rMultiple = 0.0
            )
        }

        val riskPerShare = entryPrice - stopPrice
        var maxFavorable = 0.0
        var maxAdverse = 0.0
        var exitReason = "EOD_CLOSE"
        var exitPrice = futureCandles.last().close

        for (candle in futureCandles) {
            val favorable = candle.high - entryPrice
            val adverse = entryPrice - candle.low

            if (favorable > maxFavorable) maxFavorable = favorable
            if (adverse > maxAdverse) maxAdverse = adverse

            // Barrier check: evaluate stop loss first for conservative execution
            if (candle.low <= stopPrice) {
                exitReason = "HIT_STOP"
                exitPrice = stopPrice
                break
            }
            if (candle.high >= targetPrice) {
                exitReason = "HIT_TARGET"
                exitPrice = targetPrice
                break
            }
        }

        val plPct = ((exitPrice - entryPrice) / entryPrice) * 100.0
        val rMult = if (riskPerShare > 0.0) (exitPrice - entryPrice) / riskPerShare else 0.0

        return MfeMaeResult(
            symbol = symbol,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            mfeAmount = maxFavorable,
            mfePct = (maxFavorable / entryPrice) * 100.0,
            maeAmount = maxAdverse,
            maePct = (maxAdverse / entryPrice) * 100.0,
            exitReason = exitReason,
            exitPrice = exitPrice,
            realizedPLPct = plPct,
            rMultiple = rMult
        )
    }
}
