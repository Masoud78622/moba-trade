package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.FetchResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Random

object SignalDiagnosticsRunner {

    private val IST = ZoneId.of("Asia/Kolkata")
    private val random = Random(42) // Seeded for reproducibility

    data class SignalOccurrence(
        val date: LocalDate,
        val time: LocalTime,
        val symbol: String,
        val setupType: String, // "ORB" or "Confluence"
        val score: Int,
        val isDailyBullish: Boolean,
        val entryPrice: Double,
        val return30m: Double,
        val return60m: Double,
        val returnEod: Double,
        val relativeStrength: Double = 0.0
    )

    data class DiagnosticStats(
        val totalSignals: Int,
        val winRate30m: Double,
        val winRate60m: Double,
        val winRateEod: Double,
        val avgReturn30m: Double,
        val avgReturn60m: Double,
        val avgReturnEod: Double,
        val stdDev30m: Double,
        val stdDev60m: Double,
        val stdDevEod: Double
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("======================================================================")
        println("          MOBA TRADE // QUANTITATIVE SIGNAL DIAGNOSTICS ENGINE        ")
        println("======================================================================")

        // 1. Authenticate to Angel One Client
        println("🔑 [DIAGNOSTICS] Connecting to Angel One API...")
        val sessionSuccess = AngelOneClient.ensureAuthenticated()
        if (!sessionSuccess) {
            System.err.println("❌ [DIAGNOSTICS] Connection failed. Aborting.")
            return
        }
        println("✅ [DIAGNOSTICS] Connected successfully!")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        // Load Shariah Universe
        val shariahFile = if (isWindows) "c:\\moba trade\\halal_stocks.json" else "halal_stocks.json"
        val shariahLoaded = com.mobatrade.core.halal.ShariahFilter.loadUniverse(shariahFile)
        if (!shariahLoaded) {
            System.err.println("⚠️ [DIAGNOSTICS] Warning: Shariah compliance cache failed to load. Confluence Scorer will reject all stocks.")
        } else {
            println("✅ [DIAGNOSTICS] Loaded ${com.mobatrade.core.halal.ShariahFilter.size()} Shariah-compliant stocks.")
        }

        // 2. Load watchlist
        val cacheFile = if (isWindows) File("c:\\moba trade\\watchlist_intraday.json") else File("watchlist_intraday.json")
        if (!cacheFile.exists()) {
            System.err.println("❌ [DIAGNOSTICS] Watchlist file is missing. Aborting.")
            return
        }

        val watchlistArray = JSONArray(cacheFile.readText(StandardCharsets.UTF_8))
        val watchlistStocks = ArrayList<Triple<String, String, String>>() // Symbol, Sector, Token
        for (i in 0 until watchlistArray.length()) {
            val obj = watchlistArray.getJSONObject(i)
            val symbol = obj.optString("symbol").uppercase()
            val sector = obj.optString("sector", "IT").uppercase()
            val token = obj.optString("token")
            if (symbol.isNotEmpty() && token.isNotEmpty()) {
                watchlistStocks.add(Triple(symbol, sector, token))
            }
        }

        println("📋 [DIAGNOSTICS] Loaded ${watchlistStocks.size} stocks from watchlist.")

        // 3. Fetch data for all stocks (180 days)
        val stockDailyCandles = mutableMapOf<String, List<Candle>>()
        val stock5mCandles = mutableMapOf<String, List<Candle>>()

        for ((symbol, _, token) in watchlistStocks) {
            println("📡 [DIAGNOSTICS] Fetching daily candles for $symbol...")
            val dailyResult = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = token,
                    symbol = symbol,
                    interval = "ONE_DAY",
                    limitDays = 420
                )
            }
            if (dailyResult is FetchResult.Success) {
                stockDailyCandles[symbol] = dailyResult.data
            }
            Thread.sleep(500)

            println("📡 [DIAGNOSTICS] Fetching 5-minute candles for $symbol...")
            val m5Result = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = token,
                    symbol = symbol,
                    interval = "FIVE_MINUTE",
                    limitDays = 270
                )
            }
            if (m5Result is FetchResult.Success) {
                stock5mCandles[symbol] = m5Result.data
            }
            Thread.sleep(500)
        }

        // 4. Identify all trading dates from 5m data
        val allTradingDates = stock5mCandles.values.flatMap { candles ->
            candles.map { it.timestamp.atZone(IST).toLocalDate() }
        }.distinct().sorted()

        if (allTradingDates.isEmpty()) {
            System.err.println("❌ [DIAGNOSTICS] No historical data loaded. Aborting.")
            return
        }

        println("📅 [DIAGNOSTICS] Loaded ${allTradingDates.size} trading dates from ${allTradingDates.first()} to ${allTradingDates.last()}")

        // Group 5m candles by date
        val stockCandlesByDate = watchlistStocks.associate { (symbol, _, _) ->
            symbol to (stock5mCandles[symbol]?.groupBy { it.timestamp.atZone(IST).toLocalDate() } ?: emptyMap())
        }

        // 5. Gather Signal Occurrences
        val orbSignals = mutableListOf<SignalOccurrence>()
        val confluenceSignals = mutableListOf<SignalOccurrence>()
        val controlSignals = mutableListOf<SignalOccurrence>() // Random entries for control group

        // Silence noisy prints from engine scanners during diagnostics loop
        OpeningRangeEngine.enableLogging = false

        println("⚡ Analyzing signals candle-by-candle...")

        for (date in allTradingDates) {
            OpeningRangeEngine.clearStaleEntries(date)

            // Cache day's candles and prior daily bias/ATR
            val dayCandlesMap = watchlistStocks.associate { (symbol, _, _) ->
                symbol to (stockCandlesByDate[symbol]?.get(date) ?: emptyList())
            }

            val prior5mMap = watchlistStocks.associate { (symbol, _, _) ->
                val fullList = stock5mCandles[symbol] ?: emptyList()
                val dayStartInstant = date.atTime(9, 15).atZone(IST).toInstant()
                val firstCandleIdx = fullList.indexOfFirst { it.timestamp >= dayStartInstant }
                val prior = if (firstCandleIdx != -1) fullList.subList(0, firstCandleIdx) else fullList
                symbol to prior
            }

            val dailyBiasMap = mutableMapOf<String, Boolean>()
            val dailyAtrMap = mutableMapOf<String, Double>()

            for ((symbol, _, _) in watchlistStocks) {
                val allDaily = stockDailyCandles[symbol] ?: emptyList()
                val priorDaily = allDaily.filter { it.timestamp.atZone(IST).toLocalDate().isBefore(date) }
                if (priorDaily.size >= 15) {
                    val closePrices = priorDaily.map { it.close }
                    val ema20List = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(closePrices, 20)
                    val ema20Daily = if (ema20List.isNotEmpty()) ema20List.last() else 0.0
                    val lastClose = priorDaily.last().close
                    dailyBiasMap[symbol] = lastClose > ema20Daily
                    dailyAtrMap[symbol] = calculateATR(priorDaily)
                } else {
                    dailyBiasMap[symbol] = false
                    dailyAtrMap[symbol] = 0.0
                }
            }

            // Assume standard 5m trading day has 75 cycles (from 9:15 AM to 3:30 PM)
            val maxCycles = dayCandlesMap.values.maxOfOrNull { it.size } ?: 0
            for (t in 0 until maxCycles) {
                for ((symbol, sector, token) in watchlistStocks) {
                    val candles = dayCandlesMap[symbol] ?: emptyList()
                    if (t >= candles.size) continue

                    val currentCandle = candles[t]
                    val price = currentCandle.close
                    val time = currentCandle.timestamp.atZone(IST).toLocalTime()

                    // Entry windows: morning (9:30 - 12:00) and afternoon (13:30 - 15:00)
                    val isMorning = !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(12, 0))
                    val isAfternoon = !time.isBefore(LocalTime.of(13, 30)) && !time.isAfter(LocalTime.of(15, 0))
                    val entryWindowOpen = isMorning || isAfternoon

                    if (entryWindowOpen) {
                        val prior5m = prior5mMap[symbol] ?: emptyList()
                        val visible = prior5m.takeLast(150) + candles.take(t + 1)
                        val isDailyBullish = dailyBiasMap[symbol] ?: false

                        // Check ORB Entry
                        val orbSignal = OpeningRangeEngine.detect(symbol, token, visible)
                        if (orbSignal != null && orbSignal.score >= 3) {
                            val ret30 = getForwardReturn(candles, t, 6)
                            val ret60 = getForwardReturn(candles, t, 12)
                            val retEod = getForwardReturn(candles, t, candles.size - 1 - t)

                            orbSignals.add(
                                SignalOccurrence(
                                    date = date,
                                    time = time,
                                    symbol = symbol,
                                    setupType = "ORB",
                                    score = orbSignal.score,
                                    isDailyBullish = isDailyBullish,
                                    entryPrice = price,
                                    return30m = ret30,
                                    return60m = ret60,
                                    returnEod = retEod
                                )
                            )

                            // Add a random matched control group entry on this same day/time
                            val (randSymbol, randReturn30, randReturn60, randReturnEod) = generateRandomControl(
                                date, t, watchlistStocks, dayCandlesMap
                            )
                            controlSignals.add(
                                SignalOccurrence(
                                    date = date,
                                    time = time,
                                    symbol = randSymbol,
                                    setupType = "Control",
                                    score = 0,
                                    isDailyBullish = false,
                                    entryPrice = 0.0,
                                    return30m = randReturn30,
                                    return60m = randReturn60,
                                    returnEod = randReturnEod
                                )
                            )
                        }

                        // Check Confluence Entry
                        val scorer = ConfluenceScorer(symbol, sector, adxThreshold = 18.0)
                        val scored = scorer.scoreTrade(visible)
                        if (scored.isShariahCompliant && scored.totalScore >= 3) {
                            val ret30 = getForwardReturn(candles, t, 6)
                            val ret60 = getForwardReturn(candles, t, 12)
                            val retEod = getForwardReturn(candles, t, candles.size - 1 - t)

                            // Calculate relative strength vs watchlist average
                            val stockOpen = candles.firstOrNull()?.close ?: price
                            val stockRet = if (stockOpen > 0.0) ((price - stockOpen) / stockOpen) * 100.0 else 0.0
                            var totalWatchlistReturn = 0.0
                            var activeWatchlistStocksCount = 0
                            for ((wSymbol, _, _) in watchlistStocks) {
                                val wCandles = dayCandlesMap[wSymbol] ?: emptyList()
                                val wIdx = minOf(t, wCandles.size - 1)
                                if (wIdx >= 0) {
                                    val wOpen = wCandles.firstOrNull()?.close ?: 0.0
                                    val wCurr = wCandles[wIdx].close
                                    if (wOpen > 0.0) {
                                        totalWatchlistReturn += ((wCurr - wOpen) / wOpen) * 100.0
                                        activeWatchlistStocksCount++
                                    }
                                }
                            }
                            val avgWatchlistReturn = if (activeWatchlistStocksCount > 0) totalWatchlistReturn / activeWatchlistStocksCount else 0.0
                            val relStrength = stockRet - avgWatchlistReturn

                            confluenceSignals.add(
                                SignalOccurrence(
                                    date = date,
                                    time = time,
                                    symbol = symbol,
                                    setupType = "Confluence",
                                    score = scored.totalScore,
                                    isDailyBullish = isDailyBullish,
                                    entryPrice = price,
                                    return30m = ret30,
                                    return60m = ret60,
                                    returnEod = retEod,
                                    relativeStrength = relStrength
                                )
                            )
                        }
                    }
                }
            }
        }

        // Restore logging
        OpeningRangeEngine.enableLogging = true

        // 6. Generate Statistical Summaries
        println("\n⚡ [DIAGNOSTICS] Analysis complete. Generating reports...")

        val reportDir = File("c:\\moba trade\\backtest_reports")
        reportDir.mkdirs()
        val reportFile = File(reportDir, "Signal_Diagnostics_Report.md")

        val mdReport = StringBuilder()
        mdReport.append("# Quantitative Signal Diagnostics & Forward Return Analysis Report\n\n")
        mdReport.append("Analyzes raw entry signal predictive power over **180 days** (6 months, 121 trading days) from **${allTradingDates.first()} to ${allTradingDates.last()}** without any exit rules (SL, TP, trailing stops).\n\n")

        // Helper to format tables
        fun buildStatsTable(title: String, records: List<SignalOccurrence>): String {
            val sb = StringBuilder()
            sb.append("### $title\n\n")
            sb.append("| Metric | 30 Minutes (+6 candles) | 60 Minutes (+12 candles) | End of Day (EOD) |\n")
            sb.append("|---|---|---|---|\n")

            val stats = calculateStats(records)
            sb.append("| **Total Signals** | ${stats.totalSignals} | ${stats.totalSignals} | ${stats.totalSignals} |\n")
            sb.append("| **Win Rate (Return > 0%)** | ${String.format("%.2f", stats.winRate30m)}% | ${String.format("%.2f", stats.winRate60m)}% | ${String.format("%.2f", stats.winRateEod)}% |\n")
            sb.append("| **Average Return (%)** | ${String.format("%.4f", stats.avgReturn30m)}% | ${String.format("%.4f", stats.avgReturn60m)}% | ${String.format("%.4f", stats.avgReturnEod)}% |\n")
            sb.append("| **Std Deviation (%)** | ${String.format("%.4f", stats.stdDev30m)}% | ${String.format("%.4f", stats.stdDev60m)}% | ${String.format("%.4f", stats.stdDevEod)}% |\n\n")
            return sb.toString()
        }

        // A. Summary Tables
        val totalOrbHtml = buildStatsTable("All ORB Signals (Score >= 3)", orbSignals)
        val totalConfHtml = buildStatsTable("All Confluence Signals (Score >= 3)", confluenceSignals)
        val totalControlHtml = buildStatsTable("Random Entry Control Group (Benchmark)", controlSignals)

        mdReport.append(totalOrbHtml)
        mdReport.append(totalConfHtml)
        mdReport.append(totalControlHtml)

        // B. ORB Filter breakdowns
        mdReport.append("## ORB Signal Breakdown Matrix\n\n")
        mdReport.append("| Setup Filter | Count | WR 30m (%) | Avg Ret 30m | WR 60m (%) | Avg Ret 60m | WR Eod (%) | Avg Ret Eod |\n")
        mdReport.append("|---|---|---|---|---|---|---|---|\n")

        fun appendBreakdownRow(name: String, filtered: List<SignalOccurrence>) {
            val stats = calculateStats(filtered)
            mdReport.append("| $name | ${stats.totalSignals} | ${String.format("%.2f", stats.winRate30m)}% | ${String.format("%.4f", stats.avgReturn30m)}% | ${String.format("%.2f", stats.winRate60m)}% | ${String.format("%.4f", stats.avgReturn60m)}% | ${String.format("%.2f", stats.winRateEod)}% | ${String.format("%.4f", stats.avgReturnEod)}% |\n")
        }

        // ORB splits
        appendBreakdownRow("ORB Score = 3", orbSignals.filter { it.score == 3 })
        appendBreakdownRow("ORB Score = 4", orbSignals.filter { it.score == 4 })
        appendBreakdownRow("ORB Daily Bias Bullish (EMA20)", orbSignals.filter { it.isDailyBullish })
        appendBreakdownRow("ORB Daily Bias Bearish", orbSignals.filter { !it.isDailyBullish })
        appendBreakdownRow("ORB Morning (<12:00)", orbSignals.filter { it.time.isBefore(LocalTime.of(12, 0)) })
        appendBreakdownRow("ORB Afternoon (>13:30)", orbSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) })
        mdReport.append("\n")

        // Confluence splits
        mdReport.append("## Confluence Signal Breakdown Matrix\n\n")
        mdReport.append("| Setup Filter | Count | WR 30m (%) | Avg Ret 30m | WR 60m (%) | Avg Ret 60m | WR Eod (%) | Avg Ret Eod |\n")
        mdReport.append("|---|---|---|---|---|---|---|---|\n")

        // Confluence splits
        appendBreakdownRow("Conf Score = 3", confluenceSignals.filter { it.score == 3 })
        appendBreakdownRow("Conf Score = 4", confluenceSignals.filter { it.score == 4 })
        appendBreakdownRow("Conf Daily Bias Bullish (EMA20)", confluenceSignals.filter { it.isDailyBullish })
        appendBreakdownRow("Conf Daily Bias Bearish", confluenceSignals.filter { !it.isDailyBullish })
        appendBreakdownRow("Conf Morning (<12:00)", confluenceSignals.filter { it.time.isBefore(LocalTime.of(12, 0)) })
        appendBreakdownRow("Conf Afternoon (>13:30)", confluenceSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) })
        appendBreakdownRow("Conf Afternoon & Bias Bearish", confluenceSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) && !it.isDailyBullish })
        val confAfternoonBearish = confluenceSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) && !it.isDailyBullish }
        appendBreakdownRow("Conf Afternoon & Bias Bearish & RS > 0 (Outperforming)", confAfternoonBearish.filter { it.relativeStrength > 0.0 })
        appendBreakdownRow("Conf Afternoon & Bias Bearish & RS <= 0 (Underperforming)", confAfternoonBearish.filter { it.relativeStrength <= 0.0 })
        mdReport.append("\n")

        // 7. Write Report to File
        reportFile.writeText(mdReport.toString(), StandardCharsets.UTF_8)

        // 8. Output to Console
        println("\n" + "=" .repeat(100))
        println("                               SUMMARY OF RAW SIGNAL PREDICTIVE POWER        ")
        println("=" .repeat(100))
        println("%-25s | %-6s | %-12s | %-12s | %-12s | %-12s".format("Setup", "Count", "WR 30m %", "Avg Ret 30m", "WR 60m %", "Avg Ret 60m"))
        println("-".repeat(100))

        fun printSummaryRow(name: String, recs: List<SignalOccurrence>) {
            val stats = calculateStats(recs)
            println("%-25s | %6d | %9.2f%% | %10.4f%% | %9.2f%% | %10.4f%%".format(name, stats.totalSignals, stats.winRate30m, stats.avgReturn30m, stats.winRate60m, stats.avgReturn60m))
        }

        printSummaryRow("All ORB (Score >= 3)", orbSignals)
        printSummaryRow("  └─ ORB Score = 3", orbSignals.filter { it.score == 3 })
        printSummaryRow("  └─ ORB Score = 4", orbSignals.filter { it.score == 4 })
        printSummaryRow("  └─ ORB Morning (<12)", orbSignals.filter { it.time.isBefore(LocalTime.of(12, 0)) })
        printSummaryRow("  └─ ORB Afternoon (>13:30)", orbSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) })
        println("-".repeat(100))
        printSummaryRow("All Confluence (Score >= 3)", confluenceSignals)
        printSummaryRow("  └─ Conf Score = 3", confluenceSignals.filter { it.score == 3 })
        printSummaryRow("  └─ Conf Score = 4", confluenceSignals.filter { it.score == 4 })
        printSummaryRow("  └─ Conf Morning (<12)", confluenceSignals.filter { it.time.isBefore(LocalTime.of(12, 0)) })
        printSummaryRow("  └─ Conf Afternoon (>13:30)", confluenceSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) })
        printSummaryRow("  └─ Conf Afternoon & Bias Bearish", confluenceSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) && !it.isDailyBullish })
        val confAfternoonBearish2 = confluenceSignals.filter { !it.time.isBefore(LocalTime.of(13, 30)) && !it.isDailyBullish }
        printSummaryRow("      ├─ RS > 0 (Outperforming)", confAfternoonBearish2.filter { it.relativeStrength > 0.0 })
        printSummaryRow("      └─ RS <= 0 (Underperforming)", confAfternoonBearish2.filter { it.relativeStrength <= 0.0 })
        println("-".repeat(100))
        printSummaryRow("Control Group (Random)", controlSignals)
        println("=" .repeat(100))
        println("💾 [DIAGNOSTICS] Diagnostics Report written to: ${reportFile.absolutePath}\n")
    }

    private fun getForwardReturn(candles: List<Candle>, entryIdx: Int, stepsLater: Int): Double {
        if (candles.isEmpty()) return 0.0
        val exitIdx = minOf(entryIdx + stepsLater, candles.size - 1)
        val entryPrice = candles[entryIdx].close
        val exitPrice = candles[exitIdx].close
        if (entryPrice <= 0.0) return 0.0
        return ((exitPrice - entryPrice) / entryPrice) * 100.0
    }

    private fun generateRandomControl(
        date: LocalDate,
        t: Int,
        stocks: List<Triple<String, String, String>>,
        dayCandlesMap: Map<String, List<Candle>>
    ): ControlResult {
        // Select a random stock
        val stockIdx = random.nextInt(stocks.size)
        val symbol = stocks[stockIdx].first
        val candles = dayCandlesMap[symbol] ?: emptyList()
        val entryIdx = minOf(t, candles.size - 1)

        val ret30 = getForwardReturn(candles, entryIdx, 6)
        val ret60 = getForwardReturn(candles, entryIdx, 12)
        val retEod = getForwardReturn(candles, entryIdx, candles.size - 1 - entryIdx)

        return ControlResult(symbol, ret30, ret60, retEod)
    }

    data class ControlResult(val symbol: String, val r30: Double, val r60: Double, val rEod: Double)

    private fun calculateStats(records: List<SignalOccurrence>): DiagnosticStats {
        if (records.isEmpty()) {
            return DiagnosticStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val n = records.size.toDouble()
        val count30Win = records.count { it.return30m > 0.0 }
        val count60Win = records.count { it.return60m > 0.0 }
        val countEodWin = records.count { it.returnEod > 0.0 }

        val win30 = (count30Win / n) * 100.0
        val win60 = (count60Win / n) * 100.0
        val winEod = (countEodWin / n) * 100.0

        val avg30 = records.map { it.return30m }.average()
        val avg60 = records.map { it.return60m }.average()
        val avgEod = records.map { it.returnEod }.average()

        val std30 = Math.sqrt(records.map { Math.pow(it.return30m - avg30, 2.0) }.sum() / n)
        val std60 = Math.sqrt(records.map { Math.pow(it.return60m - avg60, 2.0) }.sum() / n)
        val stdEod = Math.sqrt(records.map { Math.pow(it.returnEod - avgEod, 2.0) }.sum() / n)

        return DiagnosticStats(
            totalSignals = records.size,
            winRate30m = win30,
            winRate60m = win60,
            winRateEod = winEod,
            avgReturn30m = avg30,
            avgReturn60m = avg60,
            avgReturnEod = avgEod,
            stdDev30m = std30,
            stdDev60m = std60,
            stdDevEod = stdEod
        )
    }

    private fun calculateATR(candles: List<Candle>): Double {
        if (candles.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until candles.size) {
            val high = candles[i].high
            val low = candles[i].low
            val prevClose = candles[i - 1].close
            val tr = maxOf(high - low, Math.abs(high - prevClose), Math.abs(low - prevClose))
            total += tr
        }
        return total / (candles.size - 1)
    }
}
