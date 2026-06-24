package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.FetchResult
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.strategies.tier4.TrendTemplateScreener
import com.mobatrade.core.halal.ShariahFilter
import org.json.JSONArray
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Random

object TrendTemplateDiagnostics {

    private val IST = ZoneId.of("Asia/Kolkata")
    private val random = Random(42) // Seeded for reproducibility

    data class RawSignal(
        val symbol: String,
        val date: LocalDate,
        val entryPrice: Double,
        val stockIdx: Int,
        val isReal: Boolean,
        val niftyRegime: MarketRegime
    )

    data class SignalRecord(
        val symbol: String,
        val date: LocalDate,
        val price: Double,
        val rsScore: Double,
        val details: String,
        val return3d: Double?,
        val return5d: Double?,
        val atr: Double?,
        val targetPrice: Double?,
        val stopPrice: Double?,
        val atrExitPrice: Double?,
        val atrExitReturn: Double?,
        val atrExitDay: Int?,
        val atrHitType: String?, // "TARGET", "STOP", "EOD5", "INCOMPLETE"
        val niftyRegime: MarketRegime,
        val niftyReturn: Double,
        val niftyReturn3d: Double?,
        val niftyReturn5d: Double?
    )

    data class TradeSimulationResult(
        val return3d: Double?,
        val return5d: Double?,
        val atr: Double,
        val targetPrice: Double,
        val stopPrice: Double,
        val atrExitPrice: Double,
        val atrExitReturn: Double,
        val atrExitDay: Int,
        val atrHitType: String,
        val niftyReturn: Double,
        val niftyReturn3d: Double?,
        val niftyReturn5d: Double?
    )

    data class Stats(
        val total: Int,
        val avg3d: Double,
        val win3d: Double,
        val avgNiftyReturn3d: Double,
        val excessReturn3d: Double,
        val avg5d: Double,
        val win5d: Double,
        val avgNiftyReturn5d: Double,
        val excessReturn5d: Double,
        val avgAtr: Double,
        val winAtr: Double,
        val avgWin: Double,
        val avgLoss: Double,
        val winLossRatio: Double,
        val profitFactor: Double,
        val targetsHit: Int,
        val stopsHit: Int,
        val eodExits: Int,
        val avgNiftyReturn: Double,
        val excessReturn: Double,
        val dayExitsCount: Map<Int, Int>, // Days 1 to 5 exits
        val expectancy: Double,
        val signalsPerYear: Double,
        val avgTradesPerMonth: Double,
        val medianReturn: Double,
        val avgHoldingDays: Double,
        val medianHoldingDays: Double
    )

    data class SweepResult(
        val slMultiplier: Double,
        val tpMultiplier: Double,
        val maxHoldDays: Int,
        val freshStats: Stats,
        val tunedStats: Stats
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("======================================================================")
        println("      MOBA TRADE // TREND TEMPLATE SIGNAL DIAGNOSTICS RUNNER          ")
        println("======================================================================")

        // 1. Log in to Angel One
        val clientId = EnvLoader.get("ANGEL_CLIENT_ID") ?: AngelOneClient.DEFAULT_CLIENT_ID
        val apiKey = EnvLoader.get("ANGEL_API_KEY") ?: AngelOneClient.DEFAULT_API_KEY
        val pin = EnvLoader.get("ANGEL_PIN") ?: "3112"
        val totpSecret = EnvLoader.get("ANGEL_TOTP_SECRET") ?: AngelOneClient.DEFAULT_TOTP_SECRET

        println("🔑 Connecting to Angel One API...")
        val loggedIn = AngelOneClient.login(
            clientId = clientId,
            tradingPassword = pin,
            apiKey = apiKey,
            totpSecret = totpSecret
        )
        if (!loggedIn) {
            System.err.println("❌ Angel One login failed. Aborting.")
            return
        }
        println("✅ Connected successfully!")

        // 2. Load Shariah compliance database
        println("🕌 Loading Shariah compliance database...")
        val shariahLoaded = ShariahFilter.loadUniverse()
        if (!shariahLoaded) {
            System.err.println("⚠️ Warning: Shariah compliance cache failed to load.")
        } else {
            println("✅ Loaded ${ShariahFilter.size()} Shariah-compliant symbols.")
        }

        // 3. Load watchlisted stocks
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val cacheFile = if (isWindows) File("c:\\moba trade\\watchlist_intraday.json") else File("watchlist_intraday.json")
        
        if (!cacheFile.exists()) {
            System.err.println("❌ Watchlist file is missing. Run audit first.")
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
                if (ShariahFilter.size() > 0 && !ShariahFilter.isCompliantSymbol(symbol)) {
                    continue
                }
                watchlistStocks.add(Triple(symbol, sector, token))
            }
        }

        println("📋 Loaded ${watchlistStocks.size} stocks from watchlist.")

        // 4. Fetch Nifty (using NIFTYBEES ETF as proxy)
        println("📡 Fetching Nifty proxy daily candles (NIFTYBEES, token=10576 NSE, limitDays=2400)...")
        val niftyResult = kotlinx.coroutines.runBlocking {
            AngelOneClient.fetchHistoricalCandles(
                symbolToken = "10576",
                symbol = "NIFTYBEES-EQ",
                interval = "ONE_DAY",
                limitDays = 2400
            )
        }
        val niftyCandles = when (niftyResult) {
            is FetchResult.Success -> {
                println("   └─ Loaded ${niftyResult.data.size} Nifty daily candles.")
                niftyResult.data
            }
            is FetchResult.Failure -> {
                System.err.println("❌ Failed to fetch Nifty candles: ${niftyResult.reason}. Aborting.")
                return
            }
        }
        Thread.sleep(1000)

        // 5. Fetch daily candles for watchlist stocks
        val stockDailyCandles = mutableMapOf<String, List<Candle>>()
        for ((symbol, _, token) in watchlistStocks) {
            println("📡 Fetching daily candles for $symbol...")
            val result = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = token,
                    symbol = symbol,
                    interval = "ONE_DAY",
                    limitDays = 2400
                )
            }
            if (result is FetchResult.Success) {
                stockDailyCandles[symbol] = result.data
            } else {
                System.err.println("   └─ FAILED: ${(result as FetchResult.Failure).reason}")
            }
            Thread.sleep(450)
        }

        // 6. Gather trading dates
        val niftyDates = niftyCandles.map { it.timestamp.atZone(IST).toLocalDate() }.sorted()
        if (niftyDates.size < 230) {
            System.err.println("❌ Insufficient daily candle dates for running diagnostics.")
            return
        }

        val evalDates = niftyDates.subList(230, niftyDates.size - 5)
        
        // Split by the original tuning start date
        val tunedStartDate = LocalDate.of(2025, 11, 24)
        val freshDates = evalDates.filter { it.isBefore(tunedStartDate) }
        val tunedDates = evalDates.filter { !it.isBefore(tunedStartDate) }

        println("📅 Evaluating signals across ${evalDates.size} trading dates:")
        println("   ├─ Genuinely Fresh validation OOS period (Before $tunedStartDate): ${freshDates.size} days from ${freshDates.first()} to ${freshDates.last()}")
        println("   └─ Tuned period (IS+OOS, $tunedStartDate onwards): ${tunedDates.size} days from ${tunedDates.first()} to ${tunedDates.last()}")

        data class VersionConfig(
            val name: String,
            val rsPercentile: Double,
            val maxVcpPriceRangePct: Double,
            val slMultiplier: Double,
            val tpMultiplier: Double,
            val requirePullback: Boolean,
            val requireNiftyStage2: Boolean = false,
            val requireLiquiditySweep: Boolean = false,
            val maxHoldDays: Int = 5
        )

        val configs = listOf(
            VersionConfig("Version A (Current)", 85.0, 3.0, 0.5, 1.0, true),
            VersionConfig("Version B", 70.0, 3.0, 1.5, 3.0, true),
            VersionConfig("Version C (Baseline)", 70.0, 6.0, 1.5, 3.0, true),
            VersionConfig("Version D", 60.0, 6.0, 1.5, 3.0, true),
            VersionConfig("Version C (No Pullback)", 70.0, 6.0, 1.5, 3.0, false),
            VersionConfig("Version D (No Pullback)", 60.0, 6.0, 1.5, 3.0, false),
            VersionConfig("Version E", 70.0, 5.0, 1.0, 2.5, true),
            VersionConfig("Version E2 (Nifty Stage-2)", 70.0, 5.0, 1.0, 2.5, true, true),
            VersionConfig("Version F (Optimized Sweep)", 70.0, 5.0, 2.0, 3.5, false, false, true, 10)
        )

        val results = mutableListOf<Triple<VersionConfig, Stats, Stats>>() // Config, FreshStats, TunedStats

        var versionFIsSignals: List<RawSignal>? = null
        var versionFOosSignals: List<RawSignal>? = null

        for (config in configs) {
            val isRealSignals = mutableListOf<RawSignal>()
            val oosRealSignals = mutableListOf<RawSignal>()
            val isControlSignals = mutableListOf<RawSignal>()
            val oosControlSignals = mutableListOf<RawSignal>()

            fun processDateForConfig(date: LocalDate, isTuned: Boolean, realList: MutableList<RawSignal>, controlList: MutableList<RawSignal>) {
                val rsScores = mutableMapOf<String, Double>()
                val niftyIdx = niftyCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == date }
                if (niftyIdx == -1 || niftyIdx < 120) return
                val niftyPrices = niftyCandles.subList(0, niftyIdx + 1).map { it.close }

                for ((symbol, _, _) in watchlistStocks) {
                    val stockCandles = stockDailyCandles[symbol] ?: continue
                    val stockIdx = stockCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == date }
                    if (stockIdx == -1 || stockIdx < 120) continue
                    
                    val currentPrice = stockCandles[stockIdx].close
                    val stockPrices = stockCandles.subList(0, stockIdx + 1).map { it.close }
                    
                    val stockReturn3m = ((currentPrice - stockPrices[stockIdx - 60]) / stockPrices[stockIdx - 60]) * 100.0
                    val niftyReturn3m = ((niftyPrices.last() - niftyPrices[niftyIdx - 60]) / niftyPrices[niftyIdx - 60]) * 100.0
                    val outperformance3m = stockReturn3m - niftyReturn3m

                    val stockReturn6m = ((currentPrice - stockPrices[stockIdx - 120]) / stockPrices[stockIdx - 120]) * 100.0
                    val niftyReturn6m = ((niftyPrices.last() - niftyPrices[niftyIdx - 120]) / niftyPrices[niftyIdx - 120]) * 100.0
                    val outperformance6m = stockReturn6m - niftyReturn6m

                    rsScores[symbol] = (outperformance3m + outperformance6m) / 2.0
                }
                
                val sortedStocks = rsScores.toList().sortedBy { it.second }
                val totalStocks = sortedStocks.size
                val percentileRanks = mutableMapOf<String, Double>()
                if (totalStocks > 1) {
                    for (rank in 0 until totalStocks) {
                        val symbol = sortedStocks[rank].first
                        val percentile = (rank.toDouble() / (totalStocks - 1)) * 100.0
                        percentileRanks[symbol] = percentile
                    }
                }

                for ((symbol, _, _) in watchlistStocks) {
                    val stockCandles = stockDailyCandles[symbol] ?: continue
                    val rsPercentile = percentileRanks[symbol]
                    
                    val res = TrendTemplateScreener.screen(
                        symbol = symbol,
                        targetDate = date,
                        stockCandles = stockCandles,
                        niftyCandles = niftyCandles,
                        minRsScore = 15.0,
                        rsPercentile = rsPercentile,
                        minRsPercentile = config.rsPercentile,
                        requireVcp = true,
                        maxVcpPriceRangePct = config.maxVcpPriceRangePct,
                        minVcpVolumeContractionPct = 15.0,
                        requirePullback = config.requirePullback,
                        requireNiftyStage2 = config.requireNiftyStage2,
                        requireLiquiditySweep = config.requireLiquiditySweep
                    )

                    if (res.isTriggered) {
                        val stockIdx = stockCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == date }
                        if (stockIdx == -1) continue
                        
                        realList.add(RawSignal(symbol, date, res.price, stockIdx, true, res.niftyRegime))

                        val eligibleControlStocks = watchlistStocks.filter { it.first != symbol }
                        if (eligibleControlStocks.isNotEmpty()) {
                            val randStock = eligibleControlStocks[random.nextInt(eligibleControlStocks.size)]
                            val randSymbol = randStock.first
                            val randCandles = stockDailyCandles[randSymbol] ?: emptyList()
                            val randIdx = randCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == date }
                            if (randIdx != -1) {
                                val randEntryPrice = randCandles[randIdx].close
                                controlList.add(RawSignal(randSymbol, date, randEntryPrice, randIdx, false, res.niftyRegime))
                            }
                        }
                    }
                }
            }

            for (date in freshDates) {
                processDateForConfig(date, false, oosRealSignals, oosControlSignals)
            }
            for (date in tunedDates) {
                processDateForConfig(date, true, isRealSignals, isControlSignals)
            }

            val freshRealStats = simulateAndCompute(oosRealSignals, stockDailyCandles, niftyCandles, config.slMultiplier, config.tpMultiplier, freshDates.size, config.maxHoldDays)
            val tunedRealStats = simulateAndCompute(isRealSignals, stockDailyCandles, niftyCandles, config.slMultiplier, config.tpMultiplier, tunedDates.size, config.maxHoldDays)

            if (config.requireLiquiditySweep) {
                versionFIsSignals = isRealSignals.toList()
                versionFOosSignals = oosRealSignals.toList()
            }

            results.add(Triple(config, freshRealStats, tunedRealStats))
        }

        // Print Diagnostics Summary to Console
        println("\n" + "=" .repeat(125))
        println("                      MINERVINI PARAMETER OPTIMIZATION WALK-FORWARD COMPARISON SUMMARY (OOS PERIOD)")
        println("=" .repeat(125))
        println(String.format("%-25s | %5s | %8s | %8s | %8s | %8s | %8s | %6s | %8s | %8s | %10s",
            "Scenario Name", "Trd#", "Sig/Yr", "Trd/Mo", "AvgRet", "MedRet", "WinRate", "ExpPct", "PF", "HoldDays", "SL/TP/Time"))
        println("-" .repeat(125))
        for ((config, freshRealStats, _) in results) {
            val exitsStr = "${freshRealStats.stopsHit}/${freshRealStats.targetsHit}/${freshRealStats.eodExits}"
            println(String.format("%-25s | %5d | %8.1f | %8.2f | %7.3f%% | %7.3f%% | %7.2f%% | %7.3f%% | %6.2f | %8.1f | %10s",
                config.name,
                freshRealStats.total,
                freshRealStats.signalsPerYear,
                freshRealStats.avgTradesPerMonth,
                freshRealStats.avgAtr,
                freshRealStats.medianReturn,
                freshRealStats.winAtr,
                freshRealStats.expectancy,
                freshRealStats.profitFactor,
                freshRealStats.avgHoldingDays,
                exitsStr
            ))
        }
        println("=" .repeat(125))

        var walkForwardStats2020to2023: Stats? = null
        var walkForwardStats2024: Stats? = null
        var walkForwardStats2025: Stats? = null
        var walkForwardStats2026: Stats? = null

        var regimeBullishStats: Stats? = null
        var regimeBearishStats: Stats? = null

        if (versionFOosSignals != null && versionFIsSignals != null) {
            println("📊 Running Multi-Year Walk-Forward & Regime Validation for Version F...")
            val allVersionFSignals = versionFOosSignals!! + versionFIsSignals!!
            val allEvalDates = freshDates + tunedDates

            // Walk-Forward splits
            val dates2020to2023 = allEvalDates.filter { it.year <= 2023 }
            val dates2024 = allEvalDates.filter { it.year == 2024 }
            val dates2025 = allEvalDates.filter { it.year == 2025 }
            val dates2026 = allEvalDates.filter { it.year == 2026 }

            val signals2020to2023 = allVersionFSignals.filter { it.date.year <= 2023 }
            val signals2024 = allVersionFSignals.filter { it.date.year == 2024 }
            val signals2025 = allVersionFSignals.filter { it.date.year == 2025 }
            val signals2026 = allVersionFSignals.filter { it.date.year == 2026 }

            walkForwardStats2020to2023 = simulateAndCompute(signals2020to2023, stockDailyCandles, niftyCandles, 2.0, 3.5, dates2020to2023.size, 10)
            walkForwardStats2024 = simulateAndCompute(signals2024, stockDailyCandles, niftyCandles, 2.0, 3.5, dates2024.size, 10)
            walkForwardStats2025 = simulateAndCompute(signals2025, stockDailyCandles, niftyCandles, 2.0, 3.5, dates2025.size, 10)
            walkForwardStats2026 = simulateAndCompute(signals2026, stockDailyCandles, niftyCandles, 2.0, 3.5, dates2026.size, 10)

            // Regime splits based on Nifty 200 DMA
            val bullishDates = allEvalDates.filter { date ->
                val idx = niftyCandles.indexOfLast { it.timestamp.atZone(IST).toLocalDate() == date }
                if (idx >= 200) {
                    val close = niftyCandles[idx].close
                    val sma200 = getNiftySma(niftyCandles, idx, 200)
                    close > sma200
                } else false
            }
            val bearishSidewaysDates = allEvalDates.filter { date -> !bullishDates.contains(date) }

            val signalsBullish = allVersionFSignals.filter { bullishDates.contains(it.date) }
            val signalsBearishSideways = allVersionFSignals.filter { bearishSidewaysDates.contains(it.date) }

            regimeBullishStats = simulateAndCompute(signalsBullish, stockDailyCandles, niftyCandles, 2.0, 3.5, bullishDates.size, 10)
            regimeBearishStats = simulateAndCompute(signalsBearishSideways, stockDailyCandles, niftyCandles, 2.0, 3.5, bearishSidewaysDates.size, 10)
        }

        // Write diagnostics report to markdown file
        val diagnosticsReportDir = File("c:\\moba trade\\backtest_reports")
        diagnosticsReportDir.mkdirs()
        val reportFile = File(diagnosticsReportDir, "Trend_Template_Diagnostics_Report.md")

        val md = StringBuilder()
        md.append("# Trend Template Parameter Optimization & Exit Validation Report\n\n")
        md.append("Evaluates various configurations of Mark Minervini's Trend Template + VCP strategy over the cleaned **Shariah-compliant volatile swing universe**, using Nifty index regime filtering.\n\n")
        
        md.append("## Out-of-Sample (OOS) Period Walk-Forward Results (Before 2025-11-24)\n\n")
        md.append("| Scenario | Signals | Signals/Yr | Trades/Mo | Avg Return | Median Return | Win Rate | Expectancy | Profit Factor | Avg Hold Days | Median Hold Days | SL / TP / Time Exits | ATR Alpha |\n")
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")

        for ((config, freshRealStats, _) in results) {
            md.append("| **${config.name}** | ${freshRealStats.total} | ${String.format("%.1f", freshRealStats.signalsPerYear)} | ${String.format("%.2f", freshRealStats.avgTradesPerMonth)} | ${String.format("%.3f%%", freshRealStats.avgAtr)} | ${String.format("%.3f%%", freshRealStats.medianReturn)} | ${String.format("%.2f%%", freshRealStats.winAtr)} | **${String.format("%.3f%%", freshRealStats.expectancy)}** | ${String.format("%.2f", freshRealStats.profitFactor)} | ${String.format("%.1f", freshRealStats.avgHoldingDays)} | ${String.format("%.1f", freshRealStats.medianHoldingDays)} | ${freshRealStats.stopsHit} / ${freshRealStats.targetsHit} / ${freshRealStats.eodExits} | **${String.format("%.3f%%", freshRealStats.excessReturn)}** |\n")
        }

        md.append("\n## In-Sample (IS) / Tuned Period Walk-Forward Results (2025-11-24 Onwards)\n\n")
        md.append("| Scenario | Signals | Signals/Yr | Trades/Mo | Avg Return | Median Return | Win Rate | Expectancy | Profit Factor | Avg Hold Days | Median Hold Days | SL / TP / Time Exits | ATR Alpha |\n")
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")

        for ((config, _, tunedRealStats) in results) {
            md.append("| **${config.name}** | ${tunedRealStats.total} | ${String.format("%.1f", tunedRealStats.signalsPerYear)} | ${String.format("%.2f", tunedRealStats.avgTradesPerMonth)} | ${String.format("%.3f%%", tunedRealStats.avgAtr)} | ${String.format("%.3f%%", tunedRealStats.medianReturn)} | ${String.format("%.2f%%", tunedRealStats.winAtr)} | **${String.format("%.3f%%", tunedRealStats.expectancy)}** | ${String.format("%.2f", tunedRealStats.profitFactor)} | ${String.format("%.1f", tunedRealStats.avgHoldingDays)} | ${String.format("%.1f", tunedRealStats.medianHoldingDays)} | ${tunedRealStats.stopsHit} / ${tunedRealStats.targetsHit} / ${tunedRealStats.eodExits} | **${String.format("%.3f%%", tunedRealStats.excessReturn)}** |\n")
        }

        md.append("\n## Parameter Configuration Reference\n")
        md.append("| Scenario | RS Percentile | VCP Max Range | Stop Loss | Target | Pullback/Trigger Entry | Nifty Filter |\n")
        md.append("|---|---|---|---|---|---|---|\n")
        for (config in configs) {
            val entryType = when {
                config.requireLiquiditySweep -> "Liquidity Sweep"
                config.requirePullback -> "Pullback Bounce"
                else -> "Pivot Breakout"
            }
            md.append("| **${config.name}** | RS >= ${config.rsPercentile} | VCP <= ${config.maxVcpPriceRangePct}% | ${config.slMultiplier}x ATR | ${config.tpMultiplier}x ATR | $entryType | ${if (config.requireNiftyStage2) "Nifty Close > 50 > 200 SMA" else "Nifty not Bearish"} |\n")
        }

        if (walkForwardStats2020to2023 != null) {
            md.append("\n## Multi-Year Walk-Forward Validation (Version F - Optimized)\n")
            md.append("> Locked parameters: Stop Loss = 2.0x ATR, Take Profit = 3.5x ATR, Max Hold = 10 Days.\n\n")
            md.append("| Period | Signals | Signals/Yr | Trades/Mo | Avg Return | Median Return | Win Rate | Expectancy | Profit Factor | Avg Hold Days | Median Hold Days | SL / TP / Time Exits |\n")
            md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")

            fun appendRow(name: String, s: Stats) {
                val exits = "${s.stopsHit} / ${s.targetsHit} / ${s.eodExits}"
                md.append("| **$name** | ${s.total} | ${String.format("%.1f", s.signalsPerYear)} | ${String.format("%.2f", s.avgTradesPerMonth)} | ${String.format("%.3f%%", s.avgAtr)} | ${String.format("%.3f%%", s.medianReturn)} | ${String.format("%.2f%%", s.winAtr)} | **${String.format("%.3f%%", s.expectancy)}** | ${String.format("%.2f", s.profitFactor)} | ${String.format("%.1f", s.avgHoldingDays)} | ${String.format("%.1f", s.medianHoldingDays)} | $exits |\n")
            }

            appendRow("2020 - 2023 (Train)", walkForwardStats2020to2023)
            appendRow("2024 (Validate)", walkForwardStats2024!!)
            appendRow("2025 (Validate)", walkForwardStats2025!!)
            appendRow("2026 (Validate)", walkForwardStats2026!!)
        }

        if (regimeBullishStats != null) {
            md.append("\n## Nifty Index Regime Breakdown (Version F - Optimized)\n")
            md.append("> Locked parameters: Stop Loss = 2.0x ATR, Take Profit = 3.5x ATR, Max Hold = 10 Days.\n\n")
            md.append("| Nifty Regime | Signals | Signals/Yr | Trades/Mo | Avg Return | Median Return | Win Rate | Expectancy | Profit Factor | Avg Hold Days | Median Hold Days | SL / TP / Time Exits |\n")
            md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")

            fun appendRowRegime(name: String, s: Stats) {
                val exits = "${s.stopsHit} / ${s.targetsHit} / ${s.eodExits}"
                md.append("| **$name** | ${s.total} | ${String.format("%.1f", s.signalsPerYear)} | ${String.format("%.2f", s.avgTradesPerMonth)} | ${String.format("%.3f%%", s.avgAtr)} | ${String.format("%.3f%%", s.medianReturn)} | ${String.format("%.2f%%", s.winAtr)} | **${String.format("%.3f%%", s.expectancy)}** | ${String.format("%.2f", s.profitFactor)} | ${String.format("%.1f", s.avgHoldingDays)} | ${String.format("%.1f", s.medianHoldingDays)} | $exits |\n")
            }

            appendRowRegime("Bullish (Nifty > 200 DMA)", regimeBullishStats)
            appendRowRegime("Bearish/Sideways (Nifty <= 200 DMA)", regimeBearishStats!!)
        }

        md.append("\n## Analytical Conclusion\n")
        md.append("> [!IMPORTANT]\n")
        md.append("> Assess both **Expectancy** and **Trade Frequency** (Trades/Month) on the Out-of-Sample (OOS) period. A viable strategy needs a high positive expectancy (> 0.2%) alongside sufficient trade frequency (> 30 trades/year) to justify deployment. Compare all candidates against Version C (Baseline) and the active live Confluence strategy (+8.94% Return, 53% Win Rate).\n")

        reportFile.writeText(md.toString(), StandardCharsets.UTF_8)
        println("\n💾 Diagnostics report written to: ${reportFile.absolutePath}")
    }

    private fun simulateAndCompute(signals: List<RawSignal>, candlesMap: Map<String, List<Candle>>, niftyCandles: List<Candle>, slMultiplier: Double, tpMultiplier: Double, totalDays: Int, maxHoldDays: Int = 5): Stats {
        val records = mutableListOf<SignalRecord>()
        for (sig in signals) {
            val stockCandles = candlesMap[sig.symbol] ?: continue
            val simResult = simulateTrade(sig.symbol, sig.entryPrice, sig.stockIdx, stockCandles, niftyCandles, slMultiplier, tpMultiplier, maxHoldDays) ?: continue
            
            records.add(
                SignalRecord(
                    symbol = sig.symbol,
                    date = sig.date,
                    price = sig.entryPrice,
                    rsScore = 0.0,
                    details = "",
                    return3d = simResult.return3d,
                    return5d = simResult.return5d,
                    atr = simResult.atr,
                    targetPrice = simResult.targetPrice,
                    stopPrice = simResult.stopPrice,
                    atrExitPrice = simResult.atrExitPrice,
                    atrExitReturn = simResult.atrExitReturn,
                    atrExitDay = simResult.atrExitDay,
                    atrHitType = simResult.atrHitType,
                    niftyRegime = sig.niftyRegime,
                    niftyReturn = simResult.niftyReturn,
                    niftyReturn3d = simResult.niftyReturn3d,
                    niftyReturn5d = simResult.niftyReturn5d
                )
            )
        }
        return computeStats(records, totalDays)
    }

    private fun calculateMedian(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val size = sorted.size
        return if (size % 2 == 0) {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
        } else {
            sorted[size / 2]
        }
    }

    private fun computeStats(records: List<SignalRecord>, totalDays: Int): Stats {
        val total = records.size
        if (total == 0) {
            return Stats(
                total = 0, avg3d = 0.0, win3d = 0.0, avgNiftyReturn3d = 0.0, excessReturn3d = 0.0,
                avg5d = 0.0, win5d = 0.0, avgNiftyReturn5d = 0.0, excessReturn5d = 0.0,
                avgAtr = 0.0, winAtr = 0.0, avgWin = 0.0, avgLoss = 0.0, winLossRatio = 0.0, profitFactor = 0.0,
                targetsHit = 0, stopsHit = 0, eodExits = 0, avgNiftyReturn = 0.0, excessReturn = 0.0,
                dayExitsCount = emptyMap(), expectancy = 0.0, signalsPerYear = 0.0, avgTradesPerMonth = 0.0,
                medianReturn = 0.0, avgHoldingDays = 0.0, medianHoldingDays = 0.0
            )
        }
        
        val valid3d = records.filter { it.return3d != null }
        val avg3d = if (valid3d.isNotEmpty()) valid3d.map { it.return3d!! }.average() else 0.0
        val win3d = if (valid3d.isNotEmpty()) (valid3d.count { it.return3d!! > 0.0 }.toDouble() / valid3d.size) * 100.0 else 0.0
        val avgNiftyReturn3d = if (valid3d.isNotEmpty()) valid3d.mapNotNull { it.niftyReturn3d }.average() else 0.0
        val excessReturn3d = avg3d - avgNiftyReturn3d

        val valid5d = records.filter { it.return5d != null }
        val avg5d = if (valid5d.isNotEmpty()) valid5d.map { it.return5d!! }.average() else 0.0
        val win5d = if (valid5d.isNotEmpty()) (valid5d.count { it.return5d!! > 0.0 }.toDouble() / valid5d.size) * 100.0 else 0.0
        val avgNiftyReturn5d = if (valid5d.isNotEmpty()) valid5d.mapNotNull { it.niftyReturn5d }.average() else 0.0
        val excessReturn5d = avg5d - avgNiftyReturn5d

        val validAtr = records.filter { it.atrExitReturn != null }
        val avgAtr = if (validAtr.isNotEmpty()) validAtr.map { it.atrExitReturn!! }.average() else 0.0
        val winAtr = if (validAtr.isNotEmpty()) (validAtr.count { it.atrExitReturn!! > 0.0 }.toDouble() / validAtr.size) * 100.0 else 0.0

        val returns = validAtr.map { it.atrExitReturn!! }
        val medianReturn = calculateMedian(returns)

        val holdingDaysList = validAtr.mapNotNull { it.atrExitDay?.toDouble() }
        val avgHoldingDays = if (holdingDaysList.isNotEmpty()) holdingDaysList.average() else 0.0
        val medianHoldingDays = calculateMedian(holdingDaysList)

        val profits = validAtr.filter { it.atrExitReturn!! > 0.0 }.map { it.atrExitReturn!! }
        val losses = validAtr.filter { it.atrExitReturn!! < 0.0 }.map { Math.abs(it.atrExitReturn!!) }
        
        val avgWin = if (profits.isNotEmpty()) profits.average() else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.average() else 0.0
        val winLossRatio = if (avgLoss > 0.0) avgWin / avgLoss else 0.0

        val expectancy = (winAtr / 100.0 * avgWin) - ((1.0 - winAtr / 100.0) * avgLoss)

        val years = totalDays.toDouble() / 252.0
        val signalsPerYear = total.toDouble() / maxOf(0.01, years)
        val avgTradesPerMonth = total.toDouble() / maxOf(0.01, totalDays.toDouble() / 21.0)

        val targetsHit = validAtr.count { it.atrHitType == "TARGET" }
        val stopsHit = validAtr.count { it.atrHitType == "STOP" }
        val eodExits = validAtr.count { it.atrHitType == "EOD5" }

        val totalProfit = profits.sum()
        val totalLoss = losses.sum()
        val profitFactor = if (totalLoss > 0.0) totalProfit / totalLoss else if (totalProfit > 0.0) Double.POSITIVE_INFINITY else 0.0

        val avgNiftyReturn = validAtr.map { it.niftyReturn }.average()
        val excessReturn = avgAtr - avgNiftyReturn

        val dayExitsCount = mutableMapOf<Int, Int>()
        for (day in 1..5) {
            dayExitsCount[day] = validAtr.count { it.atrExitDay == day }
        }

        return Stats(
            total = total,
            avg3d = avg3d,
            win3d = win3d,
            avgNiftyReturn3d = avgNiftyReturn3d,
            excessReturn3d = excessReturn3d,
            avg5d = avg5d,
            win5d = win5d,
            avgNiftyReturn5d = avgNiftyReturn5d,
            excessReturn5d = excessReturn5d,
            avgAtr = avgAtr,
            winAtr = winAtr,
            avgWin = avgWin,
            avgLoss = avgLoss,
            winLossRatio = winLossRatio,
            profitFactor = profitFactor,
            targetsHit = targetsHit,
            stopsHit = stopsHit,
            eodExits = eodExits,
            avgNiftyReturn = avgNiftyReturn,
            excessReturn = excessReturn,
            dayExitsCount = dayExitsCount,
            expectancy = expectancy,
            signalsPerYear = signalsPerYear,
            avgTradesPerMonth = avgTradesPerMonth,
            medianReturn = medianReturn,
            avgHoldingDays = avgHoldingDays,
            medianHoldingDays = medianHoldingDays
        )
    }

    private fun simulateTrade(
        symbol: String,
        entryPrice: Double,
        stockIdx: Int,
        stockCandles: List<Candle>,
        niftyCandles: List<Candle>,
        stopMultiplier: Double,
        targetMultiplier: Double,
        maxHoldDays: Int = 5
    ): TradeSimulationResult? {
        val return3d = if (stockIdx + 3 < stockCandles.size) {
            val exit = stockCandles[stockIdx + 3].close
            ((exit - entryPrice) / entryPrice) * 100.0
        } else null

        val return5d = if (stockIdx + 5 < stockCandles.size) {
            val exit = stockCandles[stockIdx + 5].close
            ((exit - entryPrice) / entryPrice) * 100.0
        } else null

        val atr = calculateATR14(stockCandles, stockIdx)
        if (atr <= 0.0) return null
        
        val targetPrice = entryPrice + targetMultiplier * atr
        val stopPrice = entryPrice - stopMultiplier * atr
        
        var atrExitPrice = entryPrice
        var atrExitReturn = 0.0
        var atrExitDay = maxHoldDays
        var atrHitType = "INCOMPLETE"

        for (day in 1..maxHoldDays) {
            val checkIdx = stockIdx + day
            if (checkIdx < stockCandles.size) {
                val checkCandle = stockCandles[checkIdx]
                if (checkCandle.low <= stopPrice && checkCandle.high >= targetPrice) {
                    atrExitPrice = stopPrice
                    atrExitReturn = -stopMultiplier * (atr / entryPrice) * 100.0
                    atrExitDay = day
                    atrHitType = "STOP"
                    break
                } else if (checkCandle.low <= stopPrice) {
                    atrExitPrice = stopPrice
                    atrExitReturn = -stopMultiplier * (atr / entryPrice) * 100.0
                    atrExitDay = day
                    atrHitType = "STOP"
                    break
                } else if (checkCandle.high >= targetPrice) {
                    atrExitPrice = targetPrice
                    atrExitReturn = targetMultiplier * (atr / entryPrice) * 100.0
                    atrExitDay = day
                    atrHitType = "TARGET"
                    break
                }
                
                if (day == maxHoldDays) {
                    atrExitPrice = checkCandle.close
                    atrExitReturn = ((checkCandle.close - entryPrice) / entryPrice) * 100.0
                    atrExitDay = maxHoldDays
                    atrHitType = "EOD5"
                }
            }
        }

        // Align Nifty return over the exact holding dates
        val entryDate = stockCandles[stockIdx].timestamp.atZone(IST).toLocalDate()
        val exitDate = stockCandles[minOf(stockCandles.size - 1, stockIdx + atrExitDay)].timestamp.atZone(IST).toLocalDate()
        val entryNiftyIdx = niftyCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == entryDate }
        val exitNiftyIdx = niftyCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == exitDate }
        
        val niftyReturn = if (entryNiftyIdx != -1 && exitNiftyIdx != -1) {
            val entryNifty = niftyCandles[entryNiftyIdx].close
            val exitNifty = niftyCandles[exitNiftyIdx].close
            ((exitNifty - entryNifty) / entryNifty) * 100.0
        } else {
            0.0
        }

        val exitDate3d = if (stockIdx + 3 < stockCandles.size) stockCandles[stockIdx + 3].timestamp.atZone(IST).toLocalDate() else null
        val exitDate5d = if (stockIdx + 5 < stockCandles.size) stockCandles[stockIdx + 5].timestamp.atZone(IST).toLocalDate() else null
        
        val niftyReturn3d = if (entryNiftyIdx != -1 && exitDate3d != null) {
            val exitNiftyIdx3d = niftyCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == exitDate3d }
            if (exitNiftyIdx3d != -1) {
                val entryNifty = niftyCandles[entryNiftyIdx].close
                val exitNifty = niftyCandles[exitNiftyIdx3d].close
                ((exitNifty - entryNifty) / entryNifty) * 100.0
            } else null
        } else null

        val niftyReturn5d = if (entryNiftyIdx != -1 && exitDate5d != null) {
            val exitNiftyIdx5d = niftyCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == exitDate5d }
            if (exitNiftyIdx5d != -1) {
                val entryNifty = niftyCandles[entryNiftyIdx].close
                val exitNifty = niftyCandles[exitNiftyIdx5d].close
                ((exitNifty - entryNifty) / entryNifty) * 100.0
            } else null
        } else null

        return TradeSimulationResult(
            return3d = return3d,
            return5d = return5d,
            atr = atr,
            targetPrice = targetPrice,
            stopPrice = stopPrice,
            atrExitPrice = atrExitPrice,
            atrExitReturn = atrExitReturn,
            atrExitDay = atrExitDay,
            atrHitType = atrHitType,
            niftyReturn = niftyReturn,
            niftyReturn3d = niftyReturn3d,
            niftyReturn5d = niftyReturn5d
        )
    }

    private fun calculateATR14(candles: List<Candle>, endIdx: Int): Double {
        val period = 14
        if (endIdx < period) return 0.0
        var total = 0.0
        for (i in (endIdx - period + 1)..endIdx) {
            val current = candles[i]
            val prev = candles[i - 1]
            val tr = maxOf(
                current.high - current.low,
                Math.abs(current.high - prev.close),
                Math.abs(current.low - prev.close)
            )
            total += tr
        }
        return total / period
    }

    private fun getNiftySma(niftyCandles: List<Candle>, endIdx: Int, period: Int): Double {
        if (endIdx < period - 1) return 0.0
        var sum = 0.0
        for (i in (endIdx - period + 1)..endIdx) {
            sum += niftyCandles[i].close
        }
        return sum / period
    }
}
