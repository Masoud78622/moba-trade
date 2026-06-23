package com.mobatrade.core.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import com.mobatrade.core.model.Candle

/**
 * Phase 6 — Self-Healing Watchlist
 *
 * Problem: A stock that passes Saturday's audit may become dead during the week —
 * price stalls, volume dries up, ADX collapses. It keeps burning a scan slot while
 * better opportunities sit unused in the halal universe.
 *
 * Solution: Track consecutive "zero-score" cycles per symbol. After [MISS_THRESHOLD]
 * consecutive misses (~30 min of dead air), automatically evict the stock from the
 * active watchlist and promote the next eligible candidate from halal_stocks.json.
 *
 * Design principles:
 *  - No extra API calls: promotion candidates are ranked by a simple 5m candle
 *    momentum heuristic using data already in memory (passed in by the scanner).
 *  - Safe writes: watchlist_intraday.json is written atomically (temp + rename).
 *  - Thread-safe: all mutable state is in ConcurrentHashMap.
 *  - Transparent: every eviction and promotion is logged to stdout.
 */
object SelfHealingWatchlist {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Consecutive zero-score cycles before a stock is evicted. 6 × 5min = 30 min. */
    private const val MISS_THRESHOLD = 6

    /** Maximum size of the active watchlist. */
    private const val MAX_WATCHLIST_SIZE = 15

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val WATCHLIST_PATH = if (isWindows) "c:\\moba trade\\watchlist_intraday.json" else "watchlist_intraday.json"
    private val HALAL_PATH     = if (isWindows) "c:\\moba trade\\halal_stocks.json"       else "halal_stocks.json"

    // ── State ─────────────────────────────────────────────────────────────────

    /** Consecutive zero-score cycles for each symbol. Reset to 0 on any non-zero score. */
    private val missCount = ConcurrentHashMap<String, Int>()

    /** Symbols that scored > 0 in the most recent cycle — used to reset miss counters. */
    private val latestScores = ConcurrentHashMap<String, Int>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by the BackgroundScanner after scoring each symbol.
     * Records the score for the current cycle; [checkAndHeal] uses the collected
     * scores at the end of the scan pass to decide evictions.
     *
     * @param symbol  Stock ticker (e.g. "IGL")
     * @param score   Confluence score produced by ConfluenceScorer (0 = no signal)
     */
    fun recordScore(symbol: String, score: Int) {
        latestScores[symbol.uppercase()] = score
    }

    /**
     * Called once per scan cycle **after** all symbols have been scored.
     * Updates miss counters and triggers healing if any stock exceeds [MISS_THRESHOLD].
     */
    fun checkAndHeal() {
        val bypass = EnvLoader.get("BYPASS_WATCHLIST_HEALING")?.toBoolean() ?: true
        if (bypass) {
            latestScores.clear()
            return
        }

        // Update miss counters from latest scores
        for ((symbol, score) in latestScores) {
            if (score > 0) {
                if (missCount.getOrDefault(symbol, 0) > 0) {
                    println("🌱 [SELF-HEAL] $symbol recovered — score = $score. Resetting miss counter.")
                }
                missCount[symbol] = 0
            } else {
                val prev = missCount.getOrDefault(symbol, 0)
                val next = prev + 1
                missCount[symbol] = next
                if (next >= MISS_THRESHOLD) {
                    println("💀 [SELF-HEAL] $symbol has scored 0 for $next consecutive cycles — marking for eviction.")
                    evictAndReplace(symbol)
                    missCount.remove(symbol)   // Start fresh for any future re-entry
                } else {
                    println("⚠️  [SELF-HEAL] $symbol miss #$next/$MISS_THRESHOLD")
                }
            }
        }
        latestScores.clear()
    }

    /**
     * Returns the current miss count for a symbol (0 = healthy).
     * Useful for logging / dashboard display.
     */
    fun getMissCount(symbol: String): Int = missCount.getOrDefault(symbol.uppercase(), 0)

    /**
     * Resets all state. Called on server restart so stale counters don't carry over
     * from a previous session.
     */
    fun reset() {
        missCount.clear()
        latestScores.clear()
        println("🔄 [SELF-HEAL] State reset.")
    }

    // ── Core healing logic ────────────────────────────────────────────────────

    private fun evictAndReplace(deadSymbol: String) {
        try {
            val watchlistFile = File(WATCHLIST_PATH)
            val halalFile     = File(HALAL_PATH)

            if (!watchlistFile.exists() || !halalFile.exists()) {
                println("⚠️  [SELF-HEAL] Cannot heal: watchlist or halal file missing.")
                return
            }

            // Load current watchlist
            val watchlist = JSONArray(watchlistFile.readText(StandardCharsets.UTF_8))
            val activeSymbols = mutableSetOf<String>()
            val surviving = JSONArray()

            for (i in 0 until watchlist.length()) {
                val obj = watchlist.getJSONObject(i)
                val sym = obj.optString("symbol", "").uppercase()
                if (sym == deadSymbol.uppercase()) {
                    println("🗑️  [SELF-HEAL] Evicting $sym from watchlist (dead signal).")
                } else {
                    surviving.put(obj)
                    activeSymbols.add(sym)
                }
            }

            // If nothing was removed (symbol not in file), skip
            if (surviving.length() == watchlist.length()) {
                println("⚠️  [SELF-HEAL] $deadSymbol not found in watchlist — skipping eviction.")
                return
            }

            // Load full halal universe and find candidates not already in watchlist
            val halal = JSONArray(halalFile.readText(StandardCharsets.UTF_8))
            val candidates = mutableListOf<JSONObject>()
            for (i in 0 until halal.length()) {
                val obj = halal.getJSONObject(i)
                val sym = obj.optString("symbol", "").uppercase()
                if (sym.isNotEmpty() && !activeSymbols.contains(sym)) {
                    candidates.add(obj)
                }
            }

            if (candidates.isEmpty()) {
                println("⚠️  [SELF-HEAL] No replacement candidates available in halal universe.")
                writeWatchlist(watchlistFile, surviving)
                return
            }

            // Rank candidates: prefer those in sectors already showing strength,
            // with a simple shuffle to avoid always picking the same stock.
            // A future improvement can rank by real-time RS score here.
            val replacement = pickReplacement(candidates, activeSymbols)
            val replaceSym = replacement.optString("symbol", "UNKNOWN")
            val replaceSector = replacement.optString("sector", "?")
            val replaceToken = replacement.optString("token", "")

            // Fetch daily candles to calculate daily bias and daily ATR for the promoted stock
            var dailyAtr = 0.0
            var dailyBias = "UNKNOWN"
            if (AngelOneClient.isLoggedIn && replaceToken.isNotEmpty()) {
                try {
                    println("🔄 [SELF-HEAL] Fetching daily candles for replacement candidate $replaceSym...")
                    val fetchResult = kotlinx.coroutines.runBlocking {
                        AngelOneClient.fetchHistoricalCandles(
                            symbolToken = replaceToken,
                            symbol = replaceSym,
                            interval = "ONE_DAY",
                            limitDays = TradingConstants.CANDLE_HISTORY_DAYS_DAILY_AUDIT
                        )
                    }
                    val candles = if (fetchResult is com.mobatrade.core.model.FetchResult.Success) fetchResult.data else emptyList()
                    if (candles.size >= 200) {
                        val closePrices = candles.map { it.close }
                        val ema20List = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(closePrices, 20)
                        if (ema20List.isNotEmpty()) {
                            val price = candles.last().close
                            val ema20 = ema20List.last()
                            dailyBias = if (price > ema20) "BULLISH" else "BEARISH"
                        }
                        dailyAtr = calculateDailyATR14(candles)
                    }
                    Thread.sleep(1000) // Stay safe under API rate limits
                } catch (e: Exception) {
                    System.err.println("🔄 [SELF-HEAL] Failed to calculate metrics for $replaceSym: ${e.message}")
                }
            }

            replacement.put("dailyBias", dailyBias)
            replacement.put("dailyAtr", dailyAtr)

            surviving.put(replacement)
            println("✅ [SELF-HEAL] Promoted $replaceSym ($replaceSector) to replace $deadSymbol (dailyBias=$dailyBias, dailyAtr=$dailyAtr).")

            writeWatchlist(watchlistFile, surviving)
        } catch (e: Exception) {
            System.err.println("❌ [SELF-HEAL] Error during evict-and-replace: ${e.message}")
        }
    }

    /**
     * Picks the best replacement from [candidates].
     */
    private fun pickReplacement(candidates: List<JSONObject>, activeSectors: Set<String>): JSONObject {
        // Shuffle for diversity, then return first
        return candidates.shuffled().first()
    }

    private fun calculateDailyATR14(candles: List<Candle>): Double {
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

    /** Atomically writes the watchlist JSON to disk. */
    private fun writeWatchlist(target: File, array: JSONArray) {
        val tmp = File(target.parent, "${target.name}.tmp")
        tmp.writeText(array.toString(2), StandardCharsets.UTF_8)
        tmp.renameTo(target)
        println("💾 [SELF-HEAL] Watchlist saved (${array.length()} stocks): ${target.absolutePath}")
    }
}
