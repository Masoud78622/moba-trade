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
        if (!AngelOneClient.isLoggedIn) return
        try {
            val symbols = ShariahFilter.getAllCompliantSymbols()
            var missedTradesDetected = 0
            
            for (symbol in symbols) {
                val token = TokenIntegrityGuard.verifyAndGetToken(symbol, null) ?: continue
                val candles = AngelOneClient.fetchHistoricalCandles(symbol, token, "FIVE_MINUTE", 1)
                if (candles.isEmpty()) continue

                val dailyLow = candles.minOf { it.low }
                val dailyHigh = candles.maxOf { it.high }
                
                if (dailyLow > 0) {
                    val percentMove = ((dailyHigh - dailyLow) / dailyLow) * 100.0
                    if (percentMove >= 0.5) { // Lowered to 0.5% for aggressive testing
                        val scorer = ConfluenceScorer(symbol, "UNKNOWN")
                        val scoredTrade = scorer.scoreTrade(candles, null)
                        
                        // We missed a big trade if score is < 4
                        if (scoredTrade.totalScore < 4) {
                            println("🧠 MISSED TRADE DETECTED: $symbol moved $percentMove% but scored ${scoredTrade.totalScore}")
                            missedTradesDetected++
                            
                            // Adjust weights for strategies that DID fire but were ignored
                            for (trigger in scoredTrade.triggers) {
                                val strategyName = trigger.substringBefore(" (").trim()
                                LearnedWeights.addBonus(strategyName, 1)
                                println("🧠 -> Increased weight for $strategyName")
                            }
                        }
                    }
                }
                Thread.sleep(200) // Rate limit
            }
            println("🧠 EOD Analysis Complete. Found $missedTradesDetected missed trades.")
        } catch (e: Exception) {
            System.err.println("🧠 Analysis Failed: ${e.message}")
        }
    }
}
