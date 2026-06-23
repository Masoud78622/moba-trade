package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.FetchResult
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.strategies.tier4.TrendTemplateScreener
import com.mobatrade.core.halal.ShariahFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayList

object TrendTemplateShadowScanner {

    private val IST = ZoneId.of("Asia/Kolkata")

    @JvmStatic
    fun main(args: Array<String>) {
        println("======================================================================")
        println("      MOBA TRADE // TREND TEMPLATE VERSION F SHADOW SCANNER           ")
        println("======================================================================")

        // 1. Initialize Directories & CSV Files
        val isWindowsOs = System.getProperty("os.name").lowercase().contains("win")
        val shadowDir = if (isWindowsOs) File("c:\\moba trade\\shadow_trades") else File("shadow_trades")
        shadowDir.mkdirs()

        val signalsFile = File(shadowDir, "version_f_signals.csv")
        val resultsFile = File(shadowDir, "version_f_results.csv")

        val signalsHeader = "TradeID,Stock,EntryDate,EntryPrice,ATR,StopPrice,TargetPrice,SweepDepthAtr,RSPercentile,VCPWidth,MarketRegime\n"
        if (!signalsFile.exists() || signalsFile.readText(StandardCharsets.UTF_8).trim() == "TradeID,Stock,EntryDate,EntryPrice,ATR,StopPrice,TargetPrice,SweepDepthAtr") {
            signalsFile.writeText(signalsHeader, StandardCharsets.UTF_8)
            println("📄 Initialized/Updated version_f_signals.csv with new headers")
        }
        val resultsHeader = "TradeID,ExitDate,ExitPrice,ExitReason,PLPct,RMultiple,DaysToExit\n"
        if (!resultsFile.exists() || resultsFile.readText(StandardCharsets.UTF_8).trim() == "TradeID,ExitDate,ExitPrice,ExitReason,PLPct,RMultiple") {
            resultsFile.writeText(resultsHeader, StandardCharsets.UTF_8)
            println("📄 Initialized/Updated version_f_results.csv with new headers")
        }

        // 2. Read Existing Logs to Determine Active Trades
        val signalsList = readCsv(signalsFile)
        val resultsList = readCsv(resultsFile)
        val closedTradeIds = resultsList.map { it["TradeID"] ?: "" }.toSet()
        val activeTrades = signalsList.filter { !closedTradeIds.contains(it["TradeID"] ?: "") }

        println("📋 Active trades tracked: ${activeTrades.size}")

        // 3. Connect to Angel One SmartAPI
        val clientId = EnvLoader.get("ANGEL_CLIENT_ID") ?: AngelOneClient.DEFAULT_CLIENT_ID
        val apiKey = EnvLoader.get("ANGEL_API_KEY") ?: AngelOneClient.DEFAULT_API_KEY
        val pin = EnvLoader.get("ANGEL_PIN") ?: "3112"
        val totpSecret = EnvLoader.get("ANGEL_TOTP_SECRET") ?: AngelOneClient.DEFAULT_TOTP_SECRET

        println("🔑 Connecting to Angel One SmartAPI...")
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

        // 4. Load Shariah Compliance Watchlist
        println("🕌 Loading Shariah compliance database...")
        ShariahFilter.loadUniverse()

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

        // 5. Fetch Nifty EOD Candles to Determine Current Target Date
        println("📡 Fetching Nifty proxy daily candles NSE...")
        val niftyResult = kotlinx.coroutines.runBlocking {
            AngelOneClient.fetchHistoricalCandles(
                symbolToken = "10576",
                symbol = "NIFTYBEES-EQ",
                interval = "ONE_DAY",
                limitDays = 300 // fetch enough history for daily scan
            )
        }
        val niftyCandles = when (niftyResult) {
            is FetchResult.Success -> niftyResult.data
            is FetchResult.Failure -> {
                System.err.println("❌ Failed to fetch Nifty candles: ${niftyResult.reason}. Aborting.")
                return
            }
        }
        
        val targetDate = niftyCandles.last().timestamp.atZone(IST).toLocalDate()
        println("📅 Target Date for EOD Screening: $targetDate")

        // 6. Fetch Stock candles & Update Active Trades
        println("📡 Updating active trades against latest price action...")
        val stockDailyCandles = mutableMapOf<String, List<Candle>>()
        
        // We fetch daily candles for all stocks that are active OR in the watchlist
        val symbolsToFetch = (watchlistStocks.map { it.first } + activeTrades.map { it["Stock"] ?: "" }).distinct()
        
        for (symbol in symbolsToFetch) {
            val token = watchlistStocks.find { it.first == symbol }?.third 
                ?: activeTrades.find { it["Stock"] == symbol }?.get("TradeID")?.let { id ->
                    // Fallback to fetch token if not in current watchlist
                    watchlistArray.toList().find { (it as JSONObject).optString("symbol").uppercase() == symbol }?.let { (it as JSONObject).optString("token") }
                } ?: ""
            
            if (token.isEmpty()) continue
            
            val result = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = token,
                    symbol = symbol,
                    interval = "ONE_DAY",
                    limitDays = 300
                )
            }
            if (result is FetchResult.Success) {
                stockDailyCandles[symbol] = result.data
            }
            Thread.sleep(350)
        }

        // Update active trades lifecycle
        for (trade in activeTrades) {
            val tradeId = trade["TradeID"] ?: ""
            val symbol = trade["Stock"] ?: ""
            val entryDateStr = trade["EntryDate"] ?: ""
            val entryPrice = trade["EntryPrice"]?.toDoubleOrNull() ?: 0.0
            val atr = trade["ATR"]?.toDoubleOrNull() ?: 0.0
            val stopPrice = trade["StopPrice"]?.toDoubleOrNull() ?: 0.0
            val targetPrice = trade["TargetPrice"]?.toDoubleOrNull() ?: 0.0

            val candles = stockDailyCandles[symbol]
            if (candles == null || candles.isEmpty()) {
                println("⚠️ Candle history missing for active trade $tradeId. Skipping update.")
                continue
            }

            val entryDate = LocalDate.parse(entryDateStr)
            val entryIdx = candles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == entryDate }
            if (entryIdx == -1) {
                println("⚠️ Entry date $entryDateStr not found in candles for active trade $tradeId. Skipping.")
                continue
            }

            var dayCount = 0
            var exited = false
            for (dayIdx in (entryIdx + 1) until candles.size) {
                dayCount++
                val checkCandle = candles[dayIdx]
                val checkDate = checkCandle.timestamp.atZone(IST).toLocalDate()

                if (checkCandle.low <= stopPrice && checkCandle.high >= targetPrice) {
                    closeTrade(tradeId, checkDate, stopPrice, "STOP", entryPrice, atr, resultsFile, dayCount)
                    exited = true
                    break
                } else if (checkCandle.low <= stopPrice) {
                    closeTrade(tradeId, checkDate, stopPrice, "STOP", entryPrice, atr, resultsFile, dayCount)
                    exited = true
                    break
                } else if (checkCandle.high >= targetPrice) {
                    closeTrade(tradeId, checkDate, targetPrice, "TARGET", entryPrice, atr, resultsFile, dayCount)
                    exited = true
                    break
                }

                if (dayCount >= 10) {
                    closeTrade(tradeId, checkDate, checkCandle.close, "TIME", entryPrice, atr, resultsFile, dayCount)
                    exited = true
                    break
                }
            }

            if (!exited) {
                val currentPrice = candles.last().close
                val unrealizedPL = ((currentPrice - entryPrice) / entryPrice) * 100.0
                println("💼 ACTIVE: $symbol ($tradeId) | Days Held: $dayCount | Current: $currentPrice | Unrealized P/L: ${String.format("%.2f%%", unrealizedPL)}")
            }
        }

        // 7. Perform EOD Scan for Today's Setup Triggers
        println("\n🔎 Scanning for new Version F setups...")
        
        val rsScores = mutableMapOf<String, Double>()
        val niftyIdx = niftyCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == targetDate }
        
        if (niftyIdx != -1 && niftyIdx >= 120) {
            val niftyPrices = niftyCandles.subList(0, niftyIdx + 1).map { it.close }
            for ((symbol, _, _) in watchlistStocks) {
                val stockCandles = stockDailyCandles[symbol] ?: continue
                val stockIdx = stockCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == targetDate }
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

            val validSetups = ArrayList<String>()

            for ((symbol, _, _) in watchlistStocks) {
                val stockCandles = stockDailyCandles[symbol] ?: continue
                val rsPercentile = percentileRanks[symbol]

                val res = TrendTemplateScreener.screen(
                    symbol = symbol,
                    targetDate = targetDate,
                    stockCandles = stockCandles,
                    niftyCandles = niftyCandles,
                    minRsScore = 15.0,
                    rsPercentile = rsPercentile,
                    minRsPercentile = 70.0,
                    requireVcp = true,
                    maxVcpPriceRangePct = 5.0,
                    minVcpVolumeContractionPct = 15.0,
                    requirePullback = false,
                    requireNiftyStage2 = false,
                    requireLiquiditySweep = true
                )

                if (res.isTriggered) {
                    val stockIdx = stockCandles.indexOfFirst { it.timestamp.atZone(IST).toLocalDate() == targetDate }
                    if (stockIdx == -1) continue

                    val atr = calculateATR14(stockCandles, stockIdx)
                    val yesterdayLow = stockCandles[stockIdx - 1].low
                    val todayLow = stockCandles[stockIdx].low
                    val sweepDepth = if (atr > 0.0) (yesterdayLow - todayLow) / atr else 0.0

                    val tradeId = "F-$symbol-${targetDate.toString().replace("-", "")}"
                    
                    // Check for duplicates
                    if (signalsList.none { it["TradeID"] == tradeId }) {
                        val stop = res.price - 2.0 * atr
                        val targetPrice = res.price + 3.5 * atr
                        
                        writeCsvRow(signalsFile, listOf(
                            tradeId,
                            symbol,
                            targetDate.toString(),
                            String.format("%.2f", res.price),
                            String.format("%.2f", atr),
                            String.format("%.2f", stop),
                            String.format("%.2f", targetPrice),
                            String.format("%.3f", sweepDepth),
                            String.format("%.2f", rsPercentile ?: 0.0),
                            String.format("%.2f", res.vcpWidth),
                            res.niftyRegime.name
                        ))

                        val setupInfo = "🎯 TRIGGERED: $symbol\n" +
                                        "   ├─ Entry: ${String.format("%.2f", res.price)}\n" +
                                        "   ├─ Stop:  ${String.format("%.2f", stop)} (2.0x ATR)\n" +
                                        "   ├─ Target: ${String.format("%.2f", targetPrice)} (3.5x ATR)\n" +
                                        "   └─ Sweep Depth: ${String.format("%.3f", sweepDepth)} ATR"
                        validSetups.add(setupInfo)
                    }
                }
            }

            println("\n=======================================================")
            println("              VERSION F DAILY SCAN SUMMARY             ")
            println("=======================================================")
            println("Stocks Scanned: ${watchlistStocks.size}")
            println("Valid Setups:   ${validSetups.size}")
            println("-------------------------------------------------------")
            if (validSetups.isEmpty()) {
                println("No new setups triggered today.")
            } else {
                for (setup in validSetups) {
                    println(setup)
                }
            }
            println("=======================================================")
        }
    }

    private fun closeTrade(tradeId: String, exitDate: LocalDate, exitPrice: Double, reason: String, entryPrice: Double, atr: Double, resultsFile: File, daysHeld: Int) {
        val plPct = ((exitPrice - entryPrice) / entryPrice) * 100.0
        val rMultiple = if (atr > 0.0) (exitPrice - entryPrice) / (2.0 * atr) else 0.0

        writeCsvRow(resultsFile, listOf(
            tradeId,
            exitDate.toString(),
            String.format("%.2f", exitPrice),
            reason,
            String.format("%.3f%%", plPct),
            String.format("%.3f", rMultiple),
            daysHeld.toString()
        ))

        println("🚪 CLOSED: $tradeId exited at ${String.format("%.2f", exitPrice)} on $exitDate due to $reason | Days Held: $daysHeld | P/L: ${String.format("%.2f%%", plPct)} | R: ${String.format("%.2f", rMultiple)}")
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

    private fun writeCsvRow(file: File, row: List<String>) {
        val line = row.joinToString(",") { "\"$it\"" }
        file.appendText(line + "\n", StandardCharsets.UTF_8)
    }

    private fun readCsv(file: File): List<Map<String, String>> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines(StandardCharsets.UTF_8)
        if (lines.isEmpty()) return emptyList()
        val headers = lines[0].split(",").map { it.replace("\"", "").trim() }
        val result = ArrayList<Map<String, String>>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val values = line.split(",").map { it.replace("\"", "").trim() }
            if (values.size == headers.size) {
                val map = headers.zip(values).toMap()
                result.add(map)
            }
        }
        return result
    }
}
