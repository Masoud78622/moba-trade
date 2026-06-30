package com.mobatrade.core.engine

import com.mobatrade.core.halal.ShariahFilter
import java.time.LocalTime
import java.time.ZoneId
import com.mobatrade.core.engine.TokenIntegrityGuard

object SelfLearningEngine {
    private var learningThread: Thread? = null

    fun start() {
        if (learningThread != null && learningThread!!.isAlive) return
        learningThread = Thread {
            while (true) {
                try {
                    val nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"))
                    // Run at 16:00 (4 PM IST)
                    if (nowIst.hour == 16 && nowIst.minute == 0) {
                        println("🧠 EOD SELF-LEARNING ENGINE: Starting daily analysis...")
                        runAnalysis()
                        Thread.sleep(61_000)
                    } else {
                        Thread.sleep(30_000)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("🧠 SelfLearningEngine Exception: ${e.message}")
                    try { Thread.sleep(60_000) } catch (ie: InterruptedException) { break }
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun runAnalysis() {
        if (!AngelOneClient.ensureAuthenticated()) return
        try {
            val symbols = ShariahFilter.getAllCompliantSymbols()
            var missedTradesDetected = 0
            
            for (symbol in symbols) {
                val token = TokenIntegrityGuard.verifyAndGetToken(symbol, null) ?: continue
                // FIXED: symbolToken is first param, symbol is second
                val fetchResult = kotlinx.coroutines.runBlocking { AngelOneClient.fetchHistoricalCandles(token, symbol, "FIVE_MINUTE", 1) }
                val candles = if (fetchResult is com.mobatrade.core.model.FetchResult.Success) fetchResult.data else emptyList()
                if (candles.isEmpty()) continue

                val scorer = ConfluenceScorer(symbol, "UNKNOWN")
                val scoredTrade = scorer.scoreTrade(candles, null)
                
                val validTriggers = scoredTrade.triggers.filterNot { 
                    it.startsWith("FAILED_") || it.startsWith("NON_SHARIAH") || it.startsWith("REGIME_")
                }
                
                // Evaluate missed trade using institutional MFE/MAE analysis
                if (scoredTrade.totalScore < 4 && validTriggers.isNotEmpty() && candles.size > 15) {
                    val entryPrice = candles.last().close
                    val atr = if (scoredTrade.atr14 > 0.0) scoredTrade.atr14 else entryPrice * 0.02
                    val stopPrice = entryPrice - (1.5 * atr)
                    val targetPrice = entryPrice + (3.0 * atr)
                    
                    // Run MFE/MAE candle simulation over the daily candles
                    val mfeMae = MfeMaeAnalyzer.evaluateLongTrade(symbol, entryPrice, stopPrice, targetPrice, candles.takeLast(15))
                    
                    // Only adjust weights if MFE/MAE backtest proves positive expectancy (Hit target or positive returns)
                    if (mfeMae.exitReason == "HIT_TARGET" || mfeMae.realizedPLPct > 0.0) {
                        println("🧠 VERIFIED MISSED TRADE: $symbol | Outcome=${mfeMae.exitReason} | Realized P&L=${String.format("%.2f", mfeMae.realizedPLPct)}% | MFE=${String.format("%.2f", mfeMae.mfePct)}% | MAE=${String.format("%.2f", mfeMae.maePct)}%")
                        missedTradesDetected++
                        
                        for (trigger in validTriggers) {
                            val strategyName = trigger.substringBefore(" (").substringBefore(" [").trim()
                            LearnedWeights.addBonus(strategyName, 1)
                            println("🧠 -> Increased weight for $strategyName (Validated by MFE/MAE)")
                        }
                    } else {
                        println("🧠 FILTERED FALSE POSITIVE: $symbol moved but failed MFE/MAE expectancy check (MAE=${String.format("%.2f", mfeMae.maePct)}%)")
                    }
                }
                Thread.sleep(1000) // Rate limit (comply with Angel One 3-requests-per-second limit)
            }
            println("🧠 EOD Analysis Complete. Verified $missedTradesDetected high-expectancy missed trades.")
        } catch (e: Exception) {
            System.err.println("🧠 Analysis Failed: ${e.message}")
        }
    }
}
