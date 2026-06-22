package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.FetchResult
import com.mobatrade.core.model.MarketRegime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 9 — 1-Month Historical Backtest Simulator
 *
 * Simulates bot performance on real historical market data fetched from Angel One.
 * Evaluates the actual 15 watchlist stocks day-by-day and candle-by-candle.
 */
object HistoricalBacktestRunner {

    private val IST = ZoneId.of("Asia/Kolkata")

    data class SimPosition(
        val symbol: String,
        val entryPrice: Double,
        val quantity: Int,
        var stopLoss: Double,
        var target: Double,
        val isSwing: Boolean,
        val dailyAtr: Double,
        var peakPrice: Double,
        val entryDate: LocalDate,
        val entryTime: LocalTime = LocalTime.MIDNIGHT,
        var daysHeld: Int = 0,
        var hasBookedPartial: Boolean = false,
        val setupName: String = "Confluence",
        var maxPriceSeen: Double = entryPrice,
        var minPriceSeen: Double = entryPrice,
        val originalStopLoss: Double = stopLoss
    )

    data class TradeRecord(
        val date: LocalDate,
        val entryTime: LocalTime = LocalTime.MIDNIGHT,
        val symbol: String,
        val type: String, // "Confluence", "ORB", "VWAP Reclaim", "SWING", "SWING PARTIAL"
        val quantity: Int,
        val entryPrice: Double,
        val exitPrice: Double,
        val exitDate: LocalDate,
        val pnl: Double,
        val exitReason: String,
        val setupName: String,
        val mfeAtr: Double = 0.0,
        val maeAtr: Double = 0.0
    )

    data class ScenarioConfig(
        val name: String,
        val usePercentageBasedExits: Boolean = false,
        val confluenceSLPercent: Double = 1.0,
        val confluenceTargetPercent: Double = 2.0,
        val confluenceSLMultiplier: Double = 1.5,
        val confluenceTargetMultiplier: Double = 3.0,
        val confluenceCutoffTime: LocalTime? = null,
        val routeLateEntriesToSwing: Boolean = false,
        val maxAllocationPercent: Double = 25.0,
        val disableConfluence: Boolean = false,
        val confluenceMinScore: Int = 3,
        val maxOrbRangePct: Double? = null,
        val maxOrbEntryTime: LocalTime? = null,
        val confluenceAdxMin: Double = 18.0,
        val useFixedCapitalSizing: Boolean = false,
        val useTrailingStop: Boolean = false,
        val tslTriggerPct: Double = 1.5,
        val tslDistancePct: Double = 0.5,
        val requireEmaCrossover: Boolean = false,
        val orbMinScore: Int = 3,
        val requireBullishBias: Boolean = false,
        val requireBearishBias: Boolean = false,
        val allowMorningEntries: Boolean = true,
        val allowAfternoonEntries: Boolean = true
    )

    data class BucketStats(
        val count: Int,
        val wins: Int,
        val losses: Int,
        val netPnL: Double,
        val avgWin: Double,
        val avgLoss: Double,
        val slHits: Int,
        val tpHits: Int,
        val sqOffs: Int
    )

    data class SimulationResult(
        val scenarioName: String,
        val startingCapital: Double,
        val finalCapital: Double,
        val netPnL: Double,
        val returnPct: Double,
        val maxDrawdownPct: Double,
        val totalTrades: Int,
        val winRate: Double,
        val profitFactor: Double,
        val tradeHistory: List<TradeRecord>,
        val confluenceStats: Map<String, Any>,
        val confluenceCrossTab: Map<String, BucketStats>,
        val dailyPnLReport: String
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("======================================================================")
        println("      MOBA TRADE // 1-MONTH REAL HISTORICAL BACKTEST ENGINE          ")
        println("======================================================================")

        // 1. Log in to Angel One
        val clientId = EnvLoader.get("ANGEL_CLIENT_ID") ?: AngelOneClient.DEFAULT_CLIENT_ID
        val apiKey = EnvLoader.get("ANGEL_API_KEY") ?: AngelOneClient.DEFAULT_API_KEY
        val pin = EnvLoader.get("ANGEL_PIN") ?: "3112"
        val totpSecret = EnvLoader.get("ANGEL_TOTP_SECRET") ?: AngelOneClient.DEFAULT_TOTP_SECRET

        println("🔑 [BACKTEST] Connecting to Angel One API...")
        val loggedIn = AngelOneClient.login(
            clientId = clientId,
            tradingPassword = pin,
            apiKey = apiKey,
            totpSecret = totpSecret
        )
        if (!loggedIn) {
            System.err.println("❌ [BACKTEST] Angel One login failed. Make sure your credentials in .env are correct.")
            return
        }
        println("✅ [BACKTEST] Connected successfully!")

        println("🕌 [BACKTEST] Loading Shariah Compliance Database...")
        val shariahLoaded = com.mobatrade.core.halal.ShariahFilter.loadUniverse()
        if (shariahLoaded) {
            println("✅ [BACKTEST] Loaded ${com.mobatrade.core.halal.ShariahFilter.size()} Shariah-compliant symbols.")
        } else {
            System.err.println("❌ [BACKTEST] Failed to load Shariah compliance database.")
        }

        // 2. Load the watchlist stocks
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val cacheFile = if (isWindows) File("c:\\moba trade\\watchlist_intraday.json") else File("watchlist_intraday.json")
        
        if (!cacheFile.exists() || cacheFile.length() <= 2) {
            println("⚠️  [BACKTEST] Watchlist cache is missing or empty. Running daily audit to generate...")
            WatchlistAuditor.runDailyAudit(force = true)
            var waited = 0
            while (WatchlistAuditor.isRunning() && waited < 120) {
                Thread.sleep(1000)
                waited++
            }
        }

        if (!cacheFile.exists() || cacheFile.length() <= 2) {
            System.err.println("❌ [BACKTEST] Watchlist is unavailable. Aborting.")
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

        println("📋 [BACKTEST] Loaded ${watchlistStocks.size} stocks from watchlist:")
        watchlistStocks.forEach { println("  - ${it.first} (${it.second}, token=${it.third})") }

        // 3. Fetch data for all stocks
        val stockDailyCandles = mutableMapOf<String, List<Candle>>()
        val stock5mCandles = mutableMapOf<String, List<Candle>>()

        for ((symbol, sector, token) in watchlistStocks) {
            println("📡 [BACKTEST] Fetching daily candles for $symbol...")
            val dailyResult = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = token,
                    symbol = symbol,
                    interval = "ONE_DAY",
                    limitDays = 550 // Fetch enough history for rolling EMA & ATR
                )
            }
            if (dailyResult is FetchResult.Success) {
                stockDailyCandles[symbol] = dailyResult.data
                println("   └─ Loaded ${dailyResult.data.size} daily candles.")
            } else {
                System.err.println("   └─ FAILED daily: ${(dailyResult as FetchResult.Failure).reason}")
            }
            Thread.sleep(1000) // Cooling

            println("📡 [BACKTEST] Fetching 5-minute candles for $symbol...")
            val m5Result = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = token,
                    symbol = symbol,
                    interval = "FIVE_MINUTE",
                    limitDays = 365 // 12 months of 5-min candles
                )
            }
            if (m5Result is FetchResult.Success) {
                stock5mCandles[symbol] = m5Result.data
                println("   └─ Loaded ${m5Result.data.size} 5m candles.")
            } else {
                System.err.println("   └─ FAILED 5m: ${(m5Result as FetchResult.Failure).reason}")
            }
            Thread.sleep(1000) // Cooling
        }

        // 4. Identify all trading dates from 5m data
        val allTradingDates = stock5mCandles.values.flatMap { candles ->
            candles.map { it.timestamp.atZone(IST).toLocalDate() }
        }.distinct().sorted()

        if (allTradingDates.isEmpty()) {
            System.err.println("❌ [BACKTEST] No historical 5m data loaded. Aborting.")
            return
        }

        // Walk-forward split: 60% In-Sample, 40% Out-of-Sample
        val splitIdx = (allTradingDates.size * 0.6).toInt()
        val inSampleDates = allTradingDates.subList(0, splitIdx)
        val outOfSampleDates = allTradingDates.subList(splitIdx, allTradingDates.size)

        println("📅 [BACKTEST] Total trading days: ${allTradingDates.size} from ${allTradingDates.first()} to ${allTradingDates.last()}")
        println("📅 [BACKTEST] In-Sample (IS) dates: ${inSampleDates.size} days from ${inSampleDates.first()} to ${inSampleDates.last()}")
        println("📅 [BACKTEST] Out-of-Sample (OOS) dates: ${outOfSampleDates.size} days from ${outOfSampleDates.first()} to ${outOfSampleDates.last()}")

        // 5. Define and run Scenario 6 and Scenario 8
        val config6 = ScenarioConfig(
            name = "Scenario 6: All Combined + Confl ADX > 25 (25% Alloc, Range < 1.0% + EMA9>21 + Score==4)",
            usePercentageBasedExits = true,
            confluenceSLPercent = 1.0,
            confluenceTargetPercent = 2.0,
            confluenceCutoffTime = null,
            routeLateEntriesToSwing = false,
            maxAllocationPercent = 25.0,
            disableConfluence = false,
            confluenceMinScore = 3,
            maxOrbRangePct = 1.0,
            maxOrbEntryTime = LocalTime.of(10, 0),
            confluenceAdxMin = 25.0,
            useFixedCapitalSizing = true,
            useTrailingStop = true,
            tslTriggerPct = 1.5,
            tslDistancePct = 0.5,
            requireEmaCrossover = true,
            orbMinScore = 4
        )

        val config8 = ScenarioConfig(
            name = "Scenario 8: Confluence Afternoon & Bearish Bias (100% Alloc, ₹1L Sizing)",
            usePercentageBasedExits = true,
            confluenceSLPercent = 1.0,
            confluenceTargetPercent = 2.0,
            confluenceCutoffTime = null,
            routeLateEntriesToSwing = false,
            maxAllocationPercent = 100.0,
            disableConfluence = false,
            confluenceMinScore = 3,
            maxOrbRangePct = null,
            maxOrbEntryTime = null,
            confluenceAdxMin = 18.0,
            useFixedCapitalSizing = true,
            useTrailingStop = false,
            requireEmaCrossover = false,
            orbMinScore = 5, // Disable ORB
            requireBullishBias = false,
            requireBearishBias = true,
            allowMorningEntries = false,
            allowAfternoonEntries = true
        )

        println("▶️ Running Scenario 6...")
        val s6 = runSimulation(watchlistStocks, stockDailyCandles, stock5mCandles, allTradingDates, config6)
        val s6_is = runSimulation(watchlistStocks, stockDailyCandles, stock5mCandles, inSampleDates, config6)
        val s6_oos = runSimulation(watchlistStocks, stockDailyCandles, stock5mCandles, outOfSampleDates, config6)

        println("▶️ Running Scenario 8...")
        val s8 = runSimulation(watchlistStocks, stockDailyCandles, stock5mCandles, allTradingDates, config8)
        val s8_is = runSimulation(watchlistStocks, stockDailyCandles, stock5mCandles, inSampleDates, config8)
        val s8_oos = runSimulation(watchlistStocks, stockDailyCandles, stock5mCandles, outOfSampleDates, config8)

        // 6. Print Console Summary Comparison
        println("\n" + "=" .repeat(120))
        println("                                              SCENARIO PERFORMANCE COMPARISON SUMMARY        ")
        println("=" .repeat(120))
        println("%-30s | %-40s | %-40s".format("Metric", "Scenario 6", "Scenario 8"))
        println("-".repeat(120))
        println("%-30s | ₹%38.2f | ₹%38.2f".format("Net Profit/Loss (₹)", s6.netPnL, s8.netPnL))
        println("%-30s | %37.2f%% | %37.2f%%".format("Net Return (%)", s6.returnPct, s8.returnPct))
        println("%-30s | %37.2f%% | %37.2f%%".format("Max Portfolio DD (%)", s6.maxDrawdownPct, s8.maxDrawdownPct))
        println("%-30s | %39d | %39d".format("Total Trades Closed", s6.totalTrades, s8.totalTrades))
        println("%-30s | %37.2f%% | %37.2f%%".format("Overall Win Rate (%)", s6.winRate, s8.winRate))
        println("%-30s | %39.2f | %39.2f".format("Overall Profit Factor", s6.profitFactor, s8.profitFactor))
        println("-".repeat(120))
        println("=" .repeat(120))

        println("\n" + "=" .repeat(130))
        println("                                    SCENARIO WALK-FORWARD VALIDATION (IS VS OOS)     ")
        println("=" .repeat(130))
        println("%-30s | %-6s | %-12s | %-12s | %-12s | %-12s | %-12s | %-12s".format("Scenario", "Period", "Net P&L", "Return %", "Win Rate %", "Total Trades", "Profit Factor", "Max DD %"))
        println("-".repeat(130))
        
        fun printWFScenario(name: String, isRes: SimulationResult, oosRes: SimulationResult) {
            println("%-30s | %-6s | ₹%10.2f | %9.2f%% | %9.2f%% | %11d | %12.2f | %9.2f%%".format(name, "IS", isRes.netPnL, isRes.returnPct, isRes.winRate, isRes.totalTrades, isRes.profitFactor, isRes.maxDrawdownPct))
            println("%-30s | %-6s | ₹%10.2f | %9.2f%% | %9.2f%% | %11d | %12.2f | %9.2f%%".format("", "OOS", oosRes.netPnL, oosRes.returnPct, oosRes.winRate, oosRes.totalTrades, oosRes.profitFactor, oosRes.maxDrawdownPct))
            println("-".repeat(130))
        }
        
        printWFScenario("Scenario 6: Combined + Conf", s6_is, s6_oos)
        printWFScenario("Scenario 8: Afternoon & Bearish (100% Alloc)", s8_is, s8_oos)
        println("=" .repeat(130))

        // 7. Save detailed markdown report to file
        val reportDir = File("c:\\moba trade\\backtest_reports")
        reportDir.mkdirs()
        val reportFile = File(reportDir, "Historical_Backtest_Report.md")

        val mdReport = StringBuilder()
        mdReport.append("# Historical Backtest Diagnostics & Scenario Comparison Report\n\n")
        
        mdReport.append("## Executive Summary Comparison\n\n")
        mdReport.append("| Metric | S6: Combined + Conf | S8: Afternoon & Bearish Bias (100% Alloc) |\n")
        mdReport.append("|---|---|---|\n")
        mdReport.append("| **Net Profit/Loss (₹)** | ₹${String.format("%.2f", s6.netPnL)} | ₹${String.format("%.2f", s8.netPnL)} |\n")
        mdReport.append("| **Net Return (%)** | ${String.format("%.2f", s6.returnPct)}% | ${String.format("%.2f", s8.returnPct)}% |\n")
        mdReport.append("| **Max Drawdown (%)** | ${String.format("%.2f", s6.maxDrawdownPct)}% | ${String.format("%.2f", s8.maxDrawdownPct)}% |\n")
        mdReport.append("| **Total Closed Trades** | ${s6.totalTrades} | ${s8.totalTrades} |\n")
        mdReport.append("| **Overall Win Rate (%)** | ${String.format("%.2f", s6.winRate)}% | ${String.format("%.2f", s8.winRate)}% |\n")
        mdReport.append("| **Overall Profit Factor** | ${String.format("%.2f", s6.profitFactor)} | ${String.format("%.2f", s8.profitFactor)} |\n\n")

        mdReport.append("## Scenario Walk-Forward Validation (IS vs OOS Split)\n\n")
        mdReport.append("| Scenario | Period | Net P&L (₹) | Return (%) | Max Drawdown (%) | Win Rate (%) | Profit Factor | Trades |\n")
        mdReport.append("|---|---|---|---|---|---|---|---|\n")
        fun appendWFScenarioRow(name: String, isRes: SimulationResult, oosRes: SimulationResult) {
            mdReport.append("| $name | **In-Sample** | ₹${String.format("%.2f", isRes.netPnL)} | ${String.format("%.2f", isRes.returnPct)}% | ${String.format("%.2f", isRes.maxDrawdownPct)}% | ${String.format("%.2f", isRes.winRate)}% | ${String.format("%.2f", isRes.profitFactor)} | ${isRes.totalTrades} |\n")
            mdReport.append("| | **Out-of-Sample** | ₹${String.format("%.2f", oosRes.netPnL)} | ${String.format("%.2f", oosRes.returnPct)}% | ${String.format("%.2f", oosRes.maxDrawdownPct)}% | ${String.format("%.2f", oosRes.winRate)}% | ${String.format("%.2f", oosRes.profitFactor)} | ${oosRes.totalTrades} |\n")
            mdReport.append("|---|---|---|---|---|---|---|---|\n")
        }
        appendWFScenarioRow("Scenario 6: Combined + Conf", s6_is, s6_oos)
        appendWFScenarioRow("Scenario 8: Confluence Afternoon & Bearish Bias (100% Alloc, ₹1L Sizing)", s8_is, s8_oos)
        mdReport.append("\n")

        mdReport.append("## Confluence Setup Performance Comparison\n\n")
        mdReport.append("| Metric | S6: Combined + Conf | S8: Afternoon & Bearish Bias (100% Alloc) |\n")
        mdReport.append("|---|---|---|\n")
        val statsList = listOf(s6, s8).map { it.confluenceStats }
        mdReport.append("| **Trades** | " + statsList.joinToString(" | ") { "${it["count"]}" } + " |\n")
        mdReport.append("| **Wins / Losses** | " + statsList.joinToString(" | ") { "${it["wins"]} / ${it["losses"]}" } + " |\n")
        mdReport.append("| **Avg Win (₹)** | " + statsList.joinToString(" | ") { "₹${String.format("%.2f", it["avgWin"])}" } + " |\n")
        mdReport.append("| **Avg Loss (₹)** | " + statsList.joinToString(" | ") { "₹${String.format("%.2f", it["avgLoss"])}" } + " |\n")
        mdReport.append("| **SL Hits / TP Hits / Sq Offs** | " + statsList.joinToString(" | ") { "${it["slHits"]} / ${it["tpHits"]} / ${it["sqOffs"]}" } + " |\n")
        mdReport.append("| **Win Rate (%)** | " + statsList.joinToString(" | ") { "${String.format("%.2f", it["wr"])}%" } + " |\n")
        mdReport.append("| **Profit Factor** | " + statsList.joinToString(" | ") { "${String.format("%.2f", it["pf"])}" } + " |\n")
        mdReport.append("| **Net P&L (₹)** | " + statsList.joinToString(" | ") { "₹${String.format("%.2f", it["pnl"])}" } + " |\n\n")

        mdReport.append("## Confluence Entry Time Diagnostics (Cross-Tab for Scenario 6)\n\n")
        mdReport.append("| Time Bucket | Trades | Wins | Avg Win (₹) | Losses | Avg Loss (₹) | SL Hits | TP Hits | Sq Offs | Win Rate (%) | Net P&L (₹) |\n")
        mdReport.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((bName, stats) in s6.confluenceCrossTab) {
            mdReport.append("| $bName | ${stats.count} | ${stats.wins} | ₹${String.format("%.2f", stats.avgWin)} | ${stats.losses} | ₹${String.format("%.2f", stats.avgLoss)} | ${stats.slHits} | ${stats.tpHits} | ${stats.sqOffs} | ${String.format("%.2f", if (stats.count > 0) (stats.wins.toDouble() / stats.count) * 100.0 else 0.0)}% | ₹${String.format("%.2f", stats.netPnL)} |\n")
        }
        mdReport.append("\n")

        mdReport.append("## Detailed Closed Trades (Scenario 8: Candidate Afternoon & Bearish Bias)\n\n")
        mdReport.append("| Date | Symbol | Setup | Qty | Entry Price | Entry Time | Exit Price | Exit Date | Trade P&L (₹) | Exit Reason |\n")
        mdReport.append("|---|---|---|---|---|---|---|---|---|---|\n")
        for (tr in s8.tradeHistory) {
            mdReport.append(
                "| ${tr.date} | ${tr.symbol} | ${tr.type} | ${tr.quantity} | ₹${String.format("%.2f", tr.entryPrice)} | ${tr.entryTime} | ₹${String.format("%.2f", tr.exitPrice)} | ${tr.exitDate} | ₹${String.format("%.2f", tr.pnl)} | ${tr.exitReason} |\n"
            )
        }

        mdReport.append("\n## Daily P&L Breakdown (Scenario 8: Candidate Afternoon & Bearish Bias)\n\n")
        mdReport.append(s8.dailyPnLReport)

        mdReport.append("\n## Detailed Closed Trades (Scenario 6: Baseline Combined + Conf)\n\n")
        mdReport.append("| Date | Symbol | Setup | Qty | Entry Price | Entry Time | Exit Price | Exit Date | Trade P&L (₹) | Exit Reason |\n")
        mdReport.append("|---|---|---|---|---|---|---|---|---|---|\n")
        for (tr in s6.tradeHistory) {
            mdReport.append(
                "| ${tr.date} | ${tr.symbol} | ${tr.type} | ${tr.quantity} | ₹${String.format("%.2f", tr.entryPrice)} | ${tr.entryTime} | ₹${String.format("%.2f", tr.exitPrice)} | ${tr.exitDate} | ₹${String.format("%.2f", tr.pnl)} | ${tr.exitReason} |\n"
            )
        }

        mdReport.append("\n## Daily P&L Breakdown (Scenario 6: Baseline Combined + Conf)\n\n")
        mdReport.append(s6.dailyPnLReport)

        reportFile.writeText(mdReport.toString(), StandardCharsets.UTF_8)
        println("💾 [BACKTEST] Report written to: ${reportFile.absolutePath}")
    }

    fun runSimulation(
        watchlistStocks: List<Triple<String, String, String>>,
        stockDailyCandles: Map<String, List<Candle>>,
        stock5mCandles: Map<String, List<Candle>>,
        allTradingDates: List<LocalDate>,
        config: ScenarioConfig
    ): SimulationResult {
        var capital = 100000.0
        val startingCapital = capital
        var peakCapital = capital
        var maxDrawdownPct = 0.0

        val activePositions = mutableListOf<SimPosition>()
        val tradeHistory = mutableListOf<TradeRecord>()

        val dailyPnLReport = StringBuilder()
        dailyPnLReport.append("| Date | Starting Capital | Trades Closed | Net Profit/Loss (₹) | Net Profit/Loss (%) | Ending Capital |\n")
        dailyPnLReport.append("|---|---|---|---|---|---|\n")

        // Pre-group stock 5m candles by date to optimize lookup speed from O(N) list scan to O(1) map lookup
        val stockCandlesByDate = watchlistStocks.associate { (symbol, _, _) ->
            symbol to (stock5mCandles[symbol]?.groupBy { it.timestamp.atZone(IST).toLocalDate() } ?: emptyMap())
        }

        for (date in allTradingDates) {
            // Clear any stale ORB entries for the simulated date
            OpeningRangeEngine.clearStaleEntries(date)

            // Cache day's candles and prior candles for all watchlist stocks to avoid redundant O(N) list operations inside loops
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

            val startDayCapital = capital + activePositions.sumOf { pos ->
                val lastPrice = dayCandlesMap[pos.symbol]?.firstOrNull()?.open ?: pos.entryPrice
                pos.quantity * lastPrice
            }

            // A. Increment days held for carried-over swing positions
            activePositions.forEach { if (it.isSwing) it.daysHeld++ }

            // B. Apply Time-Based Stale Exit Rule at Market Open
            val staleExited = mutableListOf<SimPosition>()
            val swingIterator = activePositions.iterator()
            while (swingIterator.hasNext()) {
                val pos = swingIterator.next()
                if (pos.isSwing && pos.daysHeld >= 10) {
                    val dayCandles = dayCandlesMap[pos.symbol] ?: emptyList()
                    val firstCandle = dayCandles.firstOrNull()
                    if (firstCandle != null) {
                        val pnlPct = ((firstCandle.open - pos.entryPrice) / pos.entryPrice) * 100.0
                        if (pnlPct in -2.0..4.0) {
                            val exitVal = pos.quantity * firstCandle.open
                            capital += exitVal - 20.0
                            val tradePnL = exitVal - (pos.quantity * pos.entryPrice) - 40.0
                            pos.maxPriceSeen = maxOf(pos.maxPriceSeen, firstCandle.high)
                            pos.minPriceSeen = minOf(pos.minPriceSeen, firstCandle.low)
                            val mfe = if (pos.dailyAtr > 0.0) (pos.maxPriceSeen - pos.entryPrice) / pos.dailyAtr else 0.0
                            val mae = if (pos.dailyAtr > 0.0) (pos.entryPrice - pos.minPriceSeen) / pos.dailyAtr else 0.0

                            tradeHistory.add(
                                TradeRecord(
                                    date = pos.entryDate,
                                    entryTime = pos.entryTime,
                                    symbol = pos.symbol,
                                    type = "SWING",
                                    quantity = pos.quantity,
                                    entryPrice = pos.entryPrice,
                                    exitPrice = firstCandle.open,
                                    exitDate = date,
                                    pnl = tradePnL,
                                    exitReason = "Stale Swing Exit (Day ${pos.daysHeld})",
                                    setupName = pos.setupName,
                                    mfeAtr = mfe,
                                    maeAtr = mae
                                )
                            )
                            staleExited.add(pos)
                            swingIterator.remove()
                        }
                    }
                }
            }

            // C. Pre-calculate Daily Bias & ATR for each stock
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
                    val isBullish = lastClose > ema20Daily
                    dailyBiasMap[symbol] = isBullish
                    dailyAtrMap[symbol] = calculateATR(priorDaily)
                } else {
                    dailyBiasMap[symbol] = false
                    dailyAtrMap[symbol] = 0.0
                }
            }

            // D. Simulate intraday scan cycles (candle-by-candle)
            var dailyTradesClosed = staleExited.size
            var dailyPnLClosed = tradeHistory.filter { it.exitDate == date }.sumOf { it.pnl }

            // Find max 5m candles for this day
            val maxCandles = if (dayCandlesMap.values.isNotEmpty()) dayCandlesMap.values.maxOf { it.size } else 0

            for (t in 0 until maxCandles) {
                // Loop through stocks
                val tempActivePositions = ArrayList(activePositions)
                for (pos in tempActivePositions) {
                    val candles = dayCandlesMap[pos.symbol] ?: emptyList()
                    if (t >= candles.size) continue

                    val currentCandle = candles[t]
                    val price = currentCandle.close

                    // Update swing peaks
                    if (pos.isSwing) {
                        pos.peakPrice = maxOf(pos.peakPrice, currentCandle.high)
                    }

                    // Excursion tracking
                    pos.maxPriceSeen = maxOf(pos.maxPriceSeen, currentCandle.high)
                    pos.minPriceSeen = minOf(pos.minPriceSeen, currentCandle.low)

                    val pnlPct = ((price - pos.entryPrice) / pos.entryPrice) * 100.0
                    val peakGainPct = ((pos.peakPrice - pos.entryPrice) / pos.entryPrice) * 100.0

                    // Check Stop Loss hit
                    if (currentCandle.low <= pos.stopLoss) {
                        val tradePnL = (pos.stopLoss - pos.entryPrice) * pos.quantity - 40.0
                        capital += pos.quantity * pos.stopLoss - 20.0

                        pos.minPriceSeen = minOf(pos.minPriceSeen, pos.stopLoss)
                        val mfe = if (pos.dailyAtr > 0.0) (pos.maxPriceSeen - pos.entryPrice) / pos.dailyAtr else 0.0
                        val mae = if (pos.dailyAtr > 0.0) (pos.entryPrice - pos.minPriceSeen) / pos.dailyAtr else 0.0

                        val wasTrailed = pos.stopLoss != pos.originalStopLoss
                        val exitReasonStr = if (wasTrailed) "Trailing Stop Loss Hit" else "Stop Loss Hit"

                        tradeHistory.add(
                            TradeRecord(
                                date = pos.entryDate,
                                entryTime = pos.entryTime,
                                symbol = pos.symbol,
                                type = if (pos.isSwing) "SWING" else pos.setupName,
                                quantity = pos.quantity,
                                entryPrice = pos.entryPrice,
                                exitPrice = pos.stopLoss,
                                exitDate = date,
                                pnl = tradePnL,
                                exitReason = exitReasonStr,
                                setupName = pos.setupName,
                                mfeAtr = mfe,
                                maeAtr = mae
                            )
                        )
                        dailyTradesClosed++
                        dailyPnLClosed += tradePnL
                        activePositions.remove(pos)
                        continue
                    }

                    // Check Target hit
                    if (currentCandle.high >= pos.target) {
                        val tradePnL = (pos.target - pos.entryPrice) * pos.quantity - 40.0
                        capital += pos.quantity * pos.target - 20.0

                        pos.maxPriceSeen = maxOf(pos.maxPriceSeen, pos.target)
                        val mfe = if (pos.dailyAtr > 0.0) (pos.maxPriceSeen - pos.entryPrice) / pos.dailyAtr else 0.0
                        val mae = if (pos.dailyAtr > 0.0) (pos.entryPrice - pos.minPriceSeen) / pos.dailyAtr else 0.0

                        tradeHistory.add(
                            TradeRecord(
                                date = pos.entryDate,
                                entryTime = pos.entryTime,
                                symbol = pos.symbol,
                                type = if (pos.isSwing) "SWING" else pos.setupName,
                                quantity = pos.quantity,
                                entryPrice = pos.entryPrice,
                                exitPrice = pos.target,
                                exitDate = date,
                                pnl = tradePnL,
                                exitReason = "Target Hit",
                                setupName = pos.setupName,
                                mfeAtr = mfe,
                                maeAtr = mae
                            )
                        )
                        dailyTradesClosed++
                        dailyPnLClosed += tradePnL
                        activePositions.remove(pos)
                        continue
                    }

                    // Check Partial Exit at +7% for Swings (books half)
                    if (pos.isSwing && !pos.hasBookedPartial && currentCandle.high >= pos.entryPrice * 1.07) {
                        val sellQty = pos.quantity / 2
                        if (sellQty > 0) {
                            val partialExitPrice = pos.entryPrice * 1.07
                            val tradePnL = (partialExitPrice - pos.entryPrice) * sellQty - 20.0
                            capital += sellQty * partialExitPrice - 20.0
                            pos.hasBookedPartial = true
                            
                            val idx = activePositions.indexOf(pos)
                            if (idx != -1) {
                                activePositions[idx] = pos.copy(quantity = pos.quantity - sellQty, hasBookedPartial = true)
                            }
                            
                            val mfe = if (pos.dailyAtr > 0.0) (pos.maxPriceSeen - pos.entryPrice) / pos.dailyAtr else 0.0
                            val mae = if (pos.dailyAtr > 0.0) (pos.entryPrice - pos.minPriceSeen) / pos.dailyAtr else 0.0

                            tradeHistory.add(
                                TradeRecord(
                                    date = pos.entryDate,
                                    entryTime = pos.entryTime,
                                    symbol = pos.symbol,
                                    type = "SWING PARTIAL",
                                    quantity = sellQty,
                                    entryPrice = pos.entryPrice,
                                    exitPrice = partialExitPrice,
                                    exitDate = date,
                                    pnl = tradePnL,
                                    exitReason = "Partial profit hit (+7%)",
                                    setupName = pos.setupName,
                                    mfeAtr = mfe,
                                    maeAtr = mae
                                )
                            )
                            dailyPnLClosed += tradePnL
                        }
                    }

                    // Update dynamic trailing stop for swings
                    if (pos.isSwing && peakGainPct >= 5.0) {
                        val trailPct = when {
                            peakGainPct >= 15.0 -> 0.03
                            peakGainPct >= 10.0 -> 0.04
                            else                -> 0.05
                        }
                        val trailingStop = pos.peakPrice * (1.0 - trailPct)
                        val entryStop = pos.entryPrice * 0.97
                        pos.stopLoss = maxOf(pos.stopLoss, trailingStop, entryStop)
                    }

                    // Check Trailing Stop for Day Trades
                    if (!pos.isSwing && config.useTrailingStop) {
                        val peakGainPctDay = ((pos.maxPriceSeen - pos.entryPrice) / pos.entryPrice) * 100.0
                        if (peakGainPctDay >= config.tslTriggerPct) {
                            val trailingSL = pos.maxPriceSeen * (1.0 - config.tslDistancePct / 100.0)
                            pos.stopLoss = maxOf(pos.stopLoss, pos.entryPrice, trailingSL)
                        }
                    }

                    // Check 3:15 PM Square off for Day Trades
                    val time = currentCandle.timestamp.atZone(IST).toLocalTime()
                    if (!pos.isSwing && time.hour == 15 && time.minute >= 15) {
                        val tradePnL = (price - pos.entryPrice) * pos.quantity - 40.0
                        capital += pos.quantity * price - 20.0

                        pos.maxPriceSeen = maxOf(pos.maxPriceSeen, currentCandle.high)
                        pos.minPriceSeen = minOf(pos.minPriceSeen, currentCandle.low)
                        val mfe = if (pos.dailyAtr > 0.0) (pos.maxPriceSeen - pos.entryPrice) / pos.dailyAtr else 0.0
                        val mae = if (pos.dailyAtr > 0.0) (pos.entryPrice - pos.minPriceSeen) / pos.dailyAtr else 0.0

                        tradeHistory.add(
                            TradeRecord(
                                date = pos.entryDate,
                                entryTime = pos.entryTime,
                                symbol = pos.symbol,
                                type = pos.setupName,
                                quantity = pos.quantity,
                                entryPrice = pos.entryPrice,
                                exitPrice = price,
                                exitDate = date,
                                pnl = tradePnL,
                                exitReason = "Intraday Square Off (3:15 PM)",
                                setupName = pos.setupName,
                                mfeAtr = mfe,
                                maeAtr = mae
                            )
                        )
                        dailyTradesClosed++
                        dailyPnLClosed += tradePnL
                        activePositions.remove(pos)
                    }
                }

                // Evaluate new buys
                for ((symbol, sector, token) in watchlistStocks) {
                    val candles = dayCandlesMap[symbol] ?: emptyList()
                    if (t >= candles.size) continue

                    val currentCandle = candles[t]
                    val price = currentCandle.close
                    val alreadyHeld = activePositions.any { it.symbol == symbol }

                    if (!alreadyHeld) {
                        val time = currentCandle.timestamp.atZone(IST).toLocalTime()
                        val isMorning = !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(12, 0))
                        val isAfternoon = !time.isBefore(LocalTime.of(13, 30)) && !time.isAfter(LocalTime.of(15, 0))
                        val entryWindowOpen = (isMorning && config.allowMorningEntries) || (isAfternoon && config.allowAfternoonEntries)

                        val isDailyBullish = dailyBiasMap[symbol] ?: false
                        val dailyAtr = dailyAtrMap[symbol] ?: 0.0
                        val biasMatches = (!config.requireBullishBias || isDailyBullish) && (!config.requireBearishBias || !isDailyBullish)

                        if (entryWindowOpen && biasMatches && activePositions.filter { !it.isSwing }.size < 2) {
                            val prior5m = prior5mMap[symbol] ?: emptyList()
                            val visible = prior5m.takeLast(150) + candles.take(t + 1)

                            val scorer = ConfluenceScorer(symbol, sector, adxThreshold = config.confluenceAdxMin)
                            val scored = scorer.scoreTrade(visible)
                            val orbSignal = OpeningRangeEngine.detect(symbol, token, visible)

                            if (scored.isShariahCompliant) {
                                var triggerTrade = false
                                var tradeEntry = price
                                var tradeSL = price - (dailyAtr * 0.5)
                                var tradeTarget = price + (dailyAtr * 0.5 * 2.0)
                                var isSwing = scored.isSwingEligible
                                var setupName = "Confluence"

                                if (orbSignal != null && orbSignal.score >= config.orbMinScore) {
                                    val orbRangePct = (orbSignal.riskPerShare / orbSignal.entryPrice) * 100.0
                                    val rangeOk = config.maxOrbRangePct == null || orbRangePct <= config.maxOrbRangePct
                                    val timeOk = config.maxOrbEntryTime == null || !time.isAfter(config.maxOrbEntryTime)
                                    
                                    val closePrices = visible.map { it.close }
                                    val ema9List = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(closePrices, 9)
                                    val ema21List = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(closePrices, 21)
                                    val trendOk = !config.requireEmaCrossover || (ema9List.isNotEmpty() && ema21List.isNotEmpty() && ema9List.last() > ema21List.last())
                                    
                                    if (rangeOk && timeOk && trendOk) {
                                        triggerTrade = true
                                        tradeEntry = orbSignal.entryPrice
                                        if (config.usePercentageBasedExits) {
                                            tradeSL = tradeEntry * (1.0 - config.confluenceSLPercent / 100.0)
                                            tradeTarget = tradeEntry * (1.0 + config.confluenceTargetPercent / 100.0)
                                        } else {
                                            tradeSL = orbSignal.stopLoss
                                            tradeTarget = orbSignal.target
                                        }
                                        isSwing = false
                                        setupName = "ORB"
                                    }
                                } else if (scored.totalScore >= config.confluenceMinScore && !config.disableConfluence) {
                                    if (scored.isVwapReclaim && scored.vwapReclaimStopLoss > 0.0) {
                                        triggerTrade = true
                                        tradeSL = scored.vwapReclaimStopLoss
                                        tradeTarget = scored.vwapReclaimTarget
                                        isSwing = scored.isSwingEligible
                                        setupName = "VWAP Reclaim"
                                    } else {
                                        // Pure Confluence setup
                                        val isAfterCutoff = config.confluenceCutoffTime != null && !time.isBefore(config.confluenceCutoffTime)
                                        if (isAfterCutoff) {
                                            if (config.routeLateEntriesToSwing) {
                                                triggerTrade = true
                                                isSwing = true // Route directly to Swing!
                                                if (config.usePercentageBasedExits) {
                                                    tradeSL = price * (1.0 - config.confluenceSLPercent / 100.0)
                                                    tradeTarget = price * (1.0 + config.confluenceTargetPercent / 100.0)
                                                } else {
                                                    tradeSL = price - (dailyAtr * config.confluenceSLMultiplier)
                                                    tradeTarget = price + (dailyAtr * config.confluenceTargetMultiplier)
                                                }
                                                setupName = "Confluence"
                                            } else {
                                                triggerTrade = false // Skip/suppress entry after cutoff
                                            }
                                        } else {
                                            triggerTrade = true
                                            isSwing = scored.isSwingEligible
                                            if (config.usePercentageBasedExits) {
                                                tradeSL = price * (1.0 - config.confluenceSLPercent / 100.0)
                                                tradeTarget = price * (1.0 + config.confluenceTargetPercent / 100.0)
                                            } else {
                                                tradeSL = price - (dailyAtr * config.confluenceSLMultiplier)
                                                tradeTarget = price + (dailyAtr * config.confluenceTargetMultiplier)
                                            }
                                            setupName = "Confluence"
                                        }
                                    }
                                }

                                if (triggerTrade) {
                                    val riskRupees = startingCapital * 0.01
                                    val maxAlloc = startingCapital * (config.maxAllocationPercent / 100.0)
                                    val stopDistance = tradeEntry - tradeSL

                                    val qty = if (config.useFixedCapitalSizing) {
                                        (maxAlloc / tradeEntry).toInt()
                                    } else {
                                        val capQty = (maxAlloc / tradeEntry).toInt()
                                        val riskQty = if (stopDistance > 0) (riskRupees / stopDistance).toInt() else 0
                                        minOf(capQty, riskQty)
                                    }

                                    if (qty > 0) {
                                        capital -= (qty * tradeEntry) + 20.0
                                        activePositions.add(
                                            SimPosition(
                                                symbol = symbol,
                                                entryPrice = tradeEntry,
                                                quantity = qty,
                                                stopLoss = tradeSL,
                                                target = tradeTarget,
                                                isSwing = isSwing,
                                                dailyAtr = dailyAtr,
                                                peakPrice = tradeEntry,
                                                entryDate = date,
                                                entryTime = time,
                                                setupName = setupName
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val endDayCapital = capital + activePositions.sumOf { pos ->
                val lastPrice = dayCandlesMap[pos.symbol]?.lastOrNull()?.close ?: pos.entryPrice
                pos.quantity * lastPrice
            }

            peakCapital = maxOf(peakCapital, endDayCapital)
            val currentDd = ((peakCapital - endDayCapital) / peakCapital) * 100.0
            maxDrawdownPct = maxOf(maxDrawdownPct, currentDd)

            val dayReturn = endDayCapital - startDayCapital
            val dayReturnPct = (dayReturn / startDayCapital) * 100.0

            dailyPnLReport.append(
                "| $date | ₹${String.format("%,.2f", startDayCapital)} | $dailyTradesClosed | ₹${String.format("%,.2f", dayReturn)} | ${String.format("%.2f", dayReturnPct)}% | ₹${String.format("%,.2f", endDayCapital)} |\n"
            )
        }

        // Final Capital
        val finalCapital = capital + activePositions.sumOf { pos ->
            val lastPrice = stock5mCandles[pos.symbol]?.lastOrNull()?.close ?: pos.entryPrice
            pos.quantity * lastPrice
        }

        val netProfit = finalCapital - startingCapital
        val returnPct = (netProfit / startingCapital) * 100.0

        val totalWinsSum = tradeHistory.filter { it.pnl > 0 }.sumOf { it.pnl }
        val totalLossesSum = Math.abs(tradeHistory.filter { it.pnl < 0 }.sumOf { it.pnl })
        val profitFactor = if (totalLossesSum > 0) totalWinsSum / totalLossesSum else totalWinsSum

        val wins = tradeHistory.count { it.pnl > 0 }
        val totalFinished = tradeHistory.size
        val winRate = if (totalFinished > 0) (wins.toDouble() / totalFinished) * 100.0 else 0.0

        val setups = listOf("ORB", "Confluence", "VWAP Reclaim", "SWING")
        val setupStats = setups.associateWith { setup ->
            val trades = tradeHistory.filter { it.type == setup || (setup == "SWING" && it.type == "SWING PARTIAL") }
            val tCount = trades.size
            val tWins = trades.count { it.pnl > 0 }
            val tLosses = trades.count { it.pnl < 0 }
            val tPnl = trades.sumOf { it.pnl }
            val tWinRate = if (tCount > 0) (tWins.toDouble() / tCount) * 100.0 else 0.0
            val tProfitSum = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
            val tLossSum = Math.abs(trades.filter { it.pnl < 0 }.sumOf { it.pnl })
            val tPF = if (tLossSum > 0) tProfitSum / tLossSum else tProfitSum

            val winTrades = trades.filter { it.pnl > 0 }
            val lossTrades = trades.filter { it.pnl < 0 }
            val avgWin = if (winTrades.isNotEmpty()) winTrades.map { it.pnl }.average() else 0.0
            val avgLoss = if (lossTrades.isNotEmpty()) lossTrades.map { it.pnl }.average() else 0.0

            val slHits = trades.count { it.exitReason.contains("Stop Loss") }
            val tpHits = trades.count { it.exitReason.contains("Target") || it.exitReason.contains("profit") }
            val sqOffs = trades.count { it.exitReason.contains("Square Off") || it.exitReason.contains("Stale") }

            mapOf(
                "count" to tCount,
                "wins" to tWins,
                "losses" to tLosses,
                "pnl" to tPnl,
                "wr" to tWinRate,
                "pf" to tPF,
                "avgWin" to avgWin,
                "avgLoss" to avgLoss,
                "slHits" to slHits,
                "tpHits" to tpHits,
                "sqOffs" to sqOffs
            )
        }

        // Confluence Cross-Tab
        val buckets = listOf("Before 12:00", "12:00-14:00", "After 14:00")
        val crossTab = buckets.associateWith { bName ->
            val bTrades = tradeHistory.filter { tr ->
                tr.setupName == "Confluence" && when (bName) {
                    "Before 12:00" -> tr.entryTime.isBefore(LocalTime.of(12, 0))
                    "12:00-14:00" -> !tr.entryTime.isBefore(LocalTime.of(12, 0)) && tr.entryTime.isBefore(LocalTime.of(14, 0))
                    else -> !tr.entryTime.isBefore(LocalTime.of(14, 0))
                }
            }
            val bCount = bTrades.size
            val bWins = bTrades.count { it.pnl > 0 }
            val bLosses = bTrades.count { it.pnl < 0 }
            val bNet = bTrades.sumOf { it.pnl }
            val bWinTrades = bTrades.filter { it.pnl > 0 }
            val bLossTrades = bTrades.filter { it.pnl < 0 }
            val bAvgWin = if (bWinTrades.isNotEmpty()) bWinTrades.map { it.pnl }.average() else 0.0
            val bAvgLoss = if (bLossTrades.isNotEmpty()) bLossTrades.map { it.pnl }.average() else 0.0

            val slHits = bTrades.count { it.exitReason.contains("Stop Loss") }
            val tpHits = bTrades.count { it.exitReason.contains("Target") || it.exitReason.contains("profit") }
            val sqOffs = bTrades.count { it.exitReason.contains("Square Off") || it.exitReason.contains("Stale") }

            BucketStats(bCount, bWins, bLosses, bNet, bAvgWin, bAvgLoss, slHits, tpHits, sqOffs)
        }

        return SimulationResult(
            scenarioName = config.name,
            startingCapital = startingCapital,
            finalCapital = finalCapital,
            netPnL = netProfit,
            returnPct = returnPct,
            maxDrawdownPct = maxDrawdownPct,
            totalTrades = tradeHistory.size,
            winRate = winRate,
            profitFactor = profitFactor,
            tradeHistory = tradeHistory,
            confluenceStats = setupStats["Confluence"] ?: emptyMap(),
            confluenceCrossTab = crossTab,
            dailyPnLReport = dailyPnLReport.toString()
        )
    }

    private fun calculateATR(candles: List<Candle>): Double {
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
}
