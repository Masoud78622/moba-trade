package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.strategies.tier4.TechIndicators
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object WatchlistAuditor {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val HALAL_FILE_PATH = if (isWindows) "c:\\moba trade\\halal_stocks.json" else "halal_stocks.json"
    private val WATCHLIST_FILE_PATH = if (isWindows) "c:\\moba trade\\watchlist_intraday.json" else "watchlist_intraday.json"

    @Volatile
    private var isAuditRunning = false

    /**
     * Runs the daily audit to filter and compile the intraday watchlist.
     * Keeps only liquid, trending, and volatile halal stocks near their 52-week highs.
     * Saves the shortlist to watchlist_intraday.json.
     *
     * @param force If true, forces the audit to run even if the cached watchlist is from today.
     * @return True if audit ran successfully, false if skipped or failed.
     */
    fun runDailyAudit(force: Boolean = false): Boolean {
        if (isAuditRunning) {
            println("🤖 [WATCHLIST AUDIT] Audit is already running. Skipping request.")
            return false
        }

        if (!AngelOneClient.isLoggedIn) {
            System.err.println("🤖 [WATCHLIST AUDIT] Angel One is not logged in. Cannot fetch daily candles.")
            return false
        }

        val outputFile = File(WATCHLIST_FILE_PATH)
        if (!force && outputFile.exists()) {
            val lastModified = Instant.ofEpochMilli(outputFile.lastModified())
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toLocalDate()
            val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
            val lastSaturday = getMostRecentSaturday(today)
            if (!lastModified.isBefore(lastSaturday)) {
                println("🤖 [WATCHLIST AUDIT] Watchlist is already fresh since last Saturday ($lastSaturday). Skipping audit.")
                return false
            }
        }

        isAuditRunning = true
        Thread {
            try {
                println("🤖 [WATCHLIST AUDIT] Starting weekly watchlist audit on all halal stocks...")
                val halalFile = File(HALAL_FILE_PATH)
                if (!halalFile.exists()) {
                    System.err.println("🤖 [WATCHLIST AUDIT] Halal stocks file does not exist at $HALAL_FILE_PATH")
                    isAuditRunning = false
                    return@Thread
                }

                val content = halalFile.readText(StandardCharsets.UTF_8)
                val allStocksArray = JSONArray(content)
                val matchedStocksList = ArrayList<Pair<JSONObject, Double>>()

                for (i in 0 until allStocksArray.length()) {
                    val stockObj = allStocksArray.getJSONObject(i)
                    val symbol = stockObj.optString("symbol").uppercase()
                    val token = stockObj.optString("token")
                    val sector = stockObj.optString("sector", "IT")

                    if (symbol.isEmpty() || token.isEmpty()) continue

                    // 1. Fetch daily candles for the last 300 days (approx. 250 trading days)
                    println("🤖 [WATCHLIST AUDIT] Fetching daily candles for $symbol ($token)...")
                    val fetchResult = kotlinx.coroutines.runBlocking {
                        AngelOneClient.fetchHistoricalCandles(
                            symbolToken = token,
                            symbol = symbol,
                            interval = "ONE_DAY",
                            limitDays = TradingConstants.CANDLE_HISTORY_DAYS_DAILY_AUDIT
                        )
                    }
                    val candles = if (fetchResult is com.mobatrade.core.model.FetchResult.Success) fetchResult.data else emptyList()

                    // Sleep 1000ms to stay safely below Angel One rate limits
                    Thread.sleep(1000)

                    if (candles.size < 200) {
                        println("🤖 [WATCHLIST AUDIT] Skip $symbol: insufficient daily data (${candles.size} candles).")
                        continue
                    }

                    // 2. Compute metrics
                    val closePrices = candles.map { it.close }
                    val ema50List = TechIndicators.calculateEma(closePrices, 50)
                    val ema200List = TechIndicators.calculateEma(closePrices, 200)

                    if (ema50List.isEmpty() || ema200List.isEmpty()) {
                        println("🤖 [WATCHLIST AUDIT] Skip $symbol: EMA computation failed.")
                        continue
                    }

                    val price = candles.last().close
                    val ema50Daily = ema50List.last()
                    val ema200Daily = ema200List.last()

                    // Liquidity (50-day average)
                    val lookback50 = Math.min(candles.size, 50)
                    val last50 = candles.takeLast(lookback50)
                    val avgDailyVolume = last50.map { it.volume.toDouble() }.average()
                    val avgDailyValueTraded = last50.map { it.close * it.volume.toDouble() }.average()

                    // Volatility
                    val atr14 = calculateDailyATR14(candles)

                    // 52-Week Range (250 days)
                    val lookback250 = Math.min(candles.size, 250)
                    val last250 = candles.takeLast(lookback250)
                    val week52Low = last250.minOf { it.low }
                    val week52High = last250.maxOf { it.high }

                    // 3. Apply audit criteria
                    val auditPassed = passesAudit(
                        candles = candles,
                        avgDailyVolume = avgDailyVolume,
                        avgDailyValueTraded = avgDailyValueTraded,
                        ema50Daily = ema50Daily,
                        ema200Daily = ema200Daily,
                        atr14 = atr14,
                        week52Low = week52Low,
                        week52High = week52High
                    )

                    val atrRatio = if (price > 0) atr14 / price else 0.0
                    if (auditPassed) {
                        println("✅ [WATCHLIST AUDIT] $symbol PASSED audit! Price=₹$price, Vol=${avgDailyVolume.toInt()}, Value=₹${avgDailyValueTraded.toInt()}, ATR/Price=${String.format("%.3f", atrRatio)}")
                        matchedStocksList.add(Pair(stockObj, avgDailyValueTraded))
                    } else {
                        println("❌ [WATCHLIST AUDIT] $symbol FAILED audit.")
                    }
                }

                // Sort by average daily value traded descending, and take at most 17 stocks
                val top17Stocks = matchedStocksList.sortedByDescending { it.second }.take(17)
                val matchedStocks = JSONArray()
                for (item in top17Stocks) {
                    matchedStocks.put(item.first)
                }

                // Write shortlist
                outputFile.writeText(matchedStocks.toString(2), StandardCharsets.UTF_8)
                println("🤖 [WATCHLIST AUDIT] Audit completed successfully! Shortlisted ${matchedStocks.length()} of ${allStocksArray.length()} stocks saved to $WATCHLIST_FILE_PATH")
            } catch (e: Exception) {
                System.err.println("🤖 [WATCHLIST AUDIT] Exception during audit: ${e.message}")
                e.printStackTrace()
            } finally {
                isAuditRunning = false
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        return true
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

    fun passesAudit(
        candles: List<Candle>,
        avgDailyVolume: Double,
        avgDailyValueTraded: Double,
        ema50Daily: Double,
        ema200Daily: Double,
        atr14: Double,
        week52Low: Double,
        week52High: Double
    ): Boolean {
        if (candles.isEmpty()) return false
        val price = candles.last().close
        
        val matchesLiquidity = avgDailyVolume > 500_000.0 && avgDailyValueTraded > 2_00_00_000.0
        val matchesPrice = price > 100.0 && price < 10_000.0
        val matchesTrend = price > ema200Daily && ema50Daily > ema200Daily
        
        val atrRatio = if (price > 0) atr14 / price else 0.0
        val matchesVolatility = atrRatio > 0.015 && atrRatio < 0.05
        
        val matchesRange = price > week52Low * 1.20 && price > week52High * 0.85

        return matchesLiquidity && matchesPrice && matchesTrend && matchesVolatility && matchesRange
    }

    fun getMostRecentSaturday(date: LocalDate): LocalDate {
        val daysToSubtract = when (date.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> 2L
            java.time.DayOfWeek.TUESDAY -> 3L
            java.time.DayOfWeek.WEDNESDAY -> 4L
            java.time.DayOfWeek.THURSDAY -> 5L
            java.time.DayOfWeek.FRIDAY -> 6L
            java.time.DayOfWeek.SATURDAY -> 0L
            java.time.DayOfWeek.SUNDAY -> 1L
        }
        return date.minusDays(daysToSubtract)
    }
}
