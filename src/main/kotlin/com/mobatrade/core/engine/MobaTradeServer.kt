package com.mobatrade.core.engine

import com.mobatrade.core.halal.ShariahFilter
import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.MarketRegime
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import com.mobatrade.core.strategies.tier4.TrendTemplateScreener

object MobaTradeServer {
    @Volatile
    private var cachedSignalsJson: String = "[]"

    // Rolling in-memory log buffer for remote diagnostics via /logs endpoint
    private val logBuffer = java.util.concurrent.LinkedBlockingDeque<String>(500)

    fun getCachedSignalsJson(): String = cachedSignalsJson

    /** Appends a line to the rolling log buffer and also prints to stdout. */
    private fun logLine(msg: String) {
        println(msg)
        val ts = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        // Keep buffer bounded — drop oldest when full
        while (logBuffer.remainingCapacity() == 0) logBuffer.pollFirst()
        logBuffer.addLast("[$ts] $msg")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val portStr = System.getenv("PORT")
        val port = portStr?.toIntOrNull() ?: 8080
        val server = HttpServer.create(InetSocketAddress(port), 0)

        // Initialize Shariah Compliance database
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val cachePath = if (isWindows) "c:\\moba trade\\halal_stocks.json" else "halal_stocks.json"
        val cacheLoaded = ShariahFilter.loadUniverse(cachePath)
        if (cacheLoaded) {
            println("Server successfully loaded Shariah compliance database from cache: ${ShariahFilter.size()} stocks.")
        } else {
            // Load fallbacks directly if no cache exists yet
            ShariahFilter.initializeManual(
                symbols = listOf("TCS", "INFY", "WIPRO", "HCLTECH", "RELIANCE"),
                tokens = listOf("11536", "1594", "3787", "26347", "2885")
            )
            println("Server initialized Shariah compliance filter with local defaults.")
        }

        // Phase 6: reset self-healing counters on startup (stale state from a previous run is meaningless)
        SelfHealingWatchlist.reset()

        // Seed positive news sentiment for ALL halal stocks by default (0.75 = mildly positive)
        // This ensures NewsSentiment strategy contributes to scoring for every stock in the universe,
        // not just the 4 IT stocks that were hardcoded before.
        try {
            val isWindows2 = System.getProperty("os.name").lowercase().contains("win")
            val halalFile = if (isWindows2) java.io.File("c:\\moba trade\\halal_stocks.json") else java.io.File("halal_stocks.json")
            if (halalFile.exists()) {
                val halalArray = org.json.JSONArray(halalFile.readText())
                for (i in 0 until halalArray.length()) {
                    val sym = halalArray.getJSONObject(i).optString("symbol", "")
                    if (sym.isNotEmpty()) {
                        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment(sym, 0.75)
                    }
                }
                println("Seeded positive news sentiment for ${halalArray.length()} halal stocks.")
            }
        } catch (e: Exception) {
            System.err.println("Failed to seed news sentiment: ${e.message}")
        }

        // Seed sector rotation scores covering ALL sectors in our universe
        com.mobatrade.core.strategies.tier4.SectorRotation.updateSectorScores(
            mapOf(
                "IT" to 1.12,
                "PHARMA" to 1.08,
                "FMCG" to 1.06,
                "ENERGY" to 1.07,
                "METALS" to 1.05,
                "AUTO" to 1.06,
                "CEMENT" to 1.05,
                "INDUSTRIAL" to 1.05,
                "CONSUMER" to 1.05,
                "UTILITIES" to 1.05,
                "CAPITAL_GOODS" to 1.05,
                "BANKING" to 1.05,
                "INFRA" to 1.05
            )
        )

        // Set up context handlers using a single robust MainRouterHandler to prevent routing bugs
        server.createContext("/", MainRouterHandler())
        server.executor = null // Creates a default executor
        
        // Start AutoBot Engine (dormant until toggled)
        AutoBotEngine.start()
        
        // Start EOD Self-Learning Engine (daemon)
        SelfLearningEngine.start()

        // CRITICAL FIX: Load Token Integrity Guard in a background thread.
        // Previously this was synchronous in main() and blocked for 60-120s downloading 35MB,
        // causing Render's health check to fail and the container to crash.
        Thread {
            println("[STARTUP] TokenIntegrityGuard: Starting background scrip master warmup...")
            TokenIntegrityGuard.ensureMasterDownloaded()
            println("[STARTUP] TokenIntegrityGuard: Scrip master warmup complete. isReady=${TokenIntegrityGuard.isReady()}")
        }.also { it.isDaemon = true; it.name = "ScripMasterLoader" }.start()

        println("==========================================================")
        println("MOBA TRADE SERVER // COMPLIANT QUANT ENGINE")
        println("==========================================================")
        println("STATUS: RUNNING LOCAL API GATEWAY ON PORT $port")
        println("ENDPOINTS:")
        println("  -> GET http://localhost:$port/status")
        println("  -> GET http://localhost:$port/halal-stocks")
        println("  -> GET http://localhost:$port/signals")
        println("==========================================================")

        server.start()

        // Eagerly attempt Angel One login in a background thread at startup
        // so /status immediately reflects the real brokerConnected state.
        // Credentials are read from env vars first, falling back to defaults.
        Thread {
            try {
                val clientId = EnvLoader.get("ANGEL_CLIENT_ID") ?: AngelOneClient.DEFAULT_CLIENT_ID
                val apiKey = EnvLoader.get("ANGEL_API_KEY") ?: AngelOneClient.DEFAULT_API_KEY
                val pin = EnvLoader.get("ANGEL_PIN") ?: "3112"
                val totpSecret = EnvLoader.get("ANGEL_TOTP_SECRET") ?: AngelOneClient.DEFAULT_TOTP_SECRET
                println("Server startup: attempting Angel One auto-login...")
                val success = AngelOneClient.login(
                    clientId = clientId,
                    tradingPassword = pin,
                    apiKey = apiKey,
                    totpSecret = totpSecret
                )
                if (success) {
                    println("Server startup: Angel One login successful. brokerConnected = true")
                    WatchlistAuditor.runDailyAudit(force = false)
                } else {
                    System.err.println("Server startup: Angel One login failed. Check ANGEL_CLIENT_ID, ANGEL_API_KEY, ANGEL_PIN, and ANGEL_TOTP_SECRET env vars.")
                }
            } catch (e: Exception) {
                System.err.println("Server startup: login thread exception: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()

        // Start background scanner thread to update signals cache without blocking HTTP endpoints
        Thread {
            var lastWeeklyAuditDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(7)
            while (true) {
                try {
                    if (AngelOneClient.ensureAuthenticated()) {
                        if (!TokenIntegrityGuard.isReady()) {
                            logLine("BackgroundScanner: Waiting for Scrip Master to finish loading...")
                            Thread.sleep(10000)
                            continue
                        }

                        // Check if we need to run the weekly audit (Saturday >= 9 AM, OR if the file is completely missing)
                        val nowIst = java.time.ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                        val today = nowIst.toLocalDate()
                        
                        val isWindows = System.getProperty("os.name").lowercase().contains("win")
                        val watchListFile = if (isWindows) File("c:\\moba trade\\watchlist_intraday.json") else File("watchlist_intraday.json")
                        val isMissing = !watchListFile.exists() || watchListFile.length() <= 2
                        val isSaturdayRefresh = nowIst.dayOfWeek == java.time.DayOfWeek.SATURDAY && nowIst.hour >= 9 && today != lastWeeklyAuditDate

                        if (isSaturdayRefresh || isMissing) {
                            println("BackgroundScanner: ${if (isMissing) "watchlist_intraday.json missing" else "Saturday refresh"} — triggering watchlist audit...")
                            val auditStarted = WatchlistAuditor.runDailyAudit(force = true)
                            if (auditStarted) {
                                if (isSaturdayRefresh) lastWeeklyAuditDate = today
                                // Wait for the audit to finish before scanning, so we never
                                // fall back to scanning all 58 stocks on the first cycle.
                                var waited = 0
                                while (WatchlistAuditor.isRunning() && waited < 120) {
                                    Thread.sleep(5000)
                                    waited++
                                }
                                println("BackgroundScanner: Audit complete. Proceeding with Top 15 scan.")
                            }
                        }


                        val scanStart = System.currentTimeMillis()
                        logLine("BackgroundScanner: Starting scheduled scan of stock universe...")
                        val signalsArray = computeSignals()
                        cachedSignalsJson = signalsArray.toString()
                        val elapsed = (System.currentTimeMillis() - scanStart) / 1000
                        logLine("BackgroundScanner: Scan complete in ${elapsed}s. Updated signals cache with ${signalsArray.length()} items.")

                        // Sleep at least 3 minutes between scans regardless of how long the scan took,
                        // to give the API rate limits time to reset. If the scan itself took >5 min,
                        // only sleep 3 min; otherwise sleep the remaining time up to 5 min total cycle.
                        val minGapMs = 3 * 60 * 1000L
                        val remainingMs = (5 * 60 * 1000L) - (System.currentTimeMillis() - scanStart)
                        val sleepMs = if (remainingMs > minGapMs) remainingMs else minGapMs
                        logLine("BackgroundScanner: Sleeping ${sleepMs / 1000}s before next scan.")
                        Thread.sleep(sleepMs)
                    } else {
                        // Check again in 5 seconds if login has succeeded
                        Thread.sleep(5000)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("BackgroundScanner Exception: ${e.message}")
                    // On error, sleep 15 seconds before retrying
                    try { Thread.sleep(15000) } catch (ie: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun sendResponse(exchange: HttpExchange, statusCode: Int, responseBody: String) {
        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        
        // Add CORS Headers for local client security compliance
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
        exchange.responseHeaders.add("Content-Type", "application/json")
        
        val isHeadRequest = exchange.requestMethod.uppercase() == "HEAD"
        if (isHeadRequest) {
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            exchange.sendResponseHeaders(statusCode, -1) // -1 indicates no response body sent
            exchange.responseBody.close()
        } else {
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            val os: OutputStream = exchange.responseBody
            os.write(bytes)
            os.close()
        }
    }

    // Main Router Handler to bypass HttpServer longest-prefix routing bugs and trailing slash issues
    class MainRouterHandler : HttpHandler {
        private val homeHandler = HomeHandler()
        private val statusHandler = StatusHandler()
        private val halalStocksHandler = HalalStocksHandler()
        private val signalsHandler = SignalsHandler()
        private val autoBotStatusHandler = AutoBotStatusHandler()
        private val autoBotToggleHandler = AutoBotToggleHandler()
        private val learningReportHandler = LearningReportHandler()
        private val learningTriggerHandler = LearningTriggerHandler()
        private val logsHandler = LogsHandler()
        private val statisticsHandler = StatisticsHandler()

        override fun handle(exchange: HttpExchange) {
            // Options handler for CORS preflight
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
                exchange.sendResponseHeaders(204, -1)
                return
            }

            val path = exchange.requestURI.path.removeSuffix("/")
            when (path) {
                "" -> homeHandler.handle(exchange)
                "/status" -> statusHandler.handle(exchange)
                "/halal-stocks" -> halalStocksHandler.handle(exchange)
                "/signals" -> signalsHandler.handle(exchange)
                "/autobot/status" -> autoBotStatusHandler.handle(exchange)
                "/autobot/toggle" -> autoBotToggleHandler.handle(exchange)
                "/learning/report" -> learningReportHandler.handle(exchange)
                "/learning/trigger" -> learningTriggerHandler.handle(exchange)
                "/logs" -> logsHandler.handle(exchange)
                "/statistics" -> statisticsHandler.handle(exchange)
                else -> {
                    val errJson = JSONObject()
                    errJson.put("error", "Not Found")
                    errJson.put("message", "The requested path '${exchange.requestURI.path}' was not found on this server.")
                    sendResponse(exchange, 404, errJson.toString())
                }
            }
        }
    }

    // 0. Welcome / Home Handler
    class HomeHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }

            // Only respond with welcome JSON on exact root path "/", otherwise return 404
            val path = exchange.requestURI.path
            if (path == "/") {
                val welcomeJson = JSONObject()
                welcomeJson.put("name", "MobaTrade Compliant Quant Engine")
                welcomeJson.put("status", "ONLINE")
                welcomeJson.put("version", "1.0.1")
                welcomeJson.put("router", "MAIN_ROUTER_V1")
                welcomeJson.put("description", "High-performance Shariah-compliant quantitative strategy scorer API gateway.")
                
                val endpoints = JSONObject()
                endpoints.put("status", "/status")
                endpoints.put("signals", "/signals")
                endpoints.put("halal_stocks", "/halal-stocks")
                welcomeJson.put("endpoints", endpoints)
                
                welcomeJson.put("docs", "Connect this gateway endpoint to your MobaTrade mobile app under the API Diagnostics panel.")
                
                sendResponse(exchange, 200, welcomeJson.toString())
            } else {
                val errJson = JSONObject()
                errJson.put("error", "Not Found")
                errJson.put("message", "The requested path '$path' was not found on this server.")
                sendResponse(exchange, 404, errJson.toString())
            }
        }
    }

    // 1. Status Handler
    class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }

            val statusJson = JSONObject()
            statusJson.put("status", "ONLINE")
            statusJson.put("engine", "MobaTrade Core Scorer")
            statusJson.put("complianceDatabaseSize", ShariahFilter.size())
            statusJson.put("brokerConnected", AngelOneClient.isLoggedIn)
            statusJson.put("scripMasterReady", TokenIntegrityGuard.isReady())
            statusJson.put("autoBotEnabled", AutoBotEngine.isEnabled)
            statusJson.put("swingManageEnabled", AutoBotEngine.isSwingManageEnabled)
            statusJson.put("cachedSignalsLength", cachedSignalsJson.length)
            statusJson.put("serverTime", Instant.now().toString())

            val telemetryJson = JSONObject()
            telemetryJson.put("lastScanTime", ScanTelemetry.lastScanTime)
            telemetryJson.put("scanned", ScanTelemetry.scanned)
            telemetryJson.put("stage2Passed", ScanTelemetry.stage2)
            telemetryJson.put("rsHighPassed", ScanTelemetry.rsHigh)
            telemetryJson.put("vcpTightPassed", ScanTelemetry.vcpTight)
            telemetryJson.put("sweepPassed", ScanTelemetry.sweep)
            telemetryJson.put("triggered", ScanTelemetry.triggered)
            statusJson.put("scanTelemetry", telemetryJson)

            sendResponse(exchange, 200, statusJson.toString())
        }
    }

    // 2. Halal Stocks Universe Handler
    class HalalStocksHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }

            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cacheFile = if (isWindows) File("c:\\moba trade\\halal_stocks.json") else File("halal_stocks.json")
            if (cacheFile.exists()) {
                val content = cacheFile.readText(StandardCharsets.UTF_8)
                sendResponse(exchange, 200, content)
            } else {
                // Return default fallback list in JSON format
                val defaultList = JSONArray()
                val fallbacks = listOf("TCS", "INFY", "WIPRO", "HCLTECH", "RELIANCE")
                for (sym in fallbacks) {
                    val obj = JSONObject()
                    obj.put("symbol", sym)
                    obj.put("exchange", "NSE")
                    obj.put("compliant", true)
                    defaultList.put(obj)
                }
                sendResponse(exchange, 200, defaultList.toString())
            }
        }
    }

    // 3. Live Signals Generation Scorer
    class SignalsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }

            try {
                // Instantly return the cached signals json to prevent client / proxy timeouts
                sendResponse(exchange, 200, cachedSignalsJson)
            } catch (e: Exception) {
                e.printStackTrace()
                val err = JSONObject()
                err.put("error", "Failed to retrieve signals")
                err.put("details", e.message)
                sendResponse(exchange, 500, err.toString())
            }
        }
    }

    // 4. AutoBot Status Handler
    class AutoBotStatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val statusJson = JSONObject()
            statusJson.put("isEnabled", AutoBotEngine.isEnabled)
            statusJson.put("isSwingManageEnabled", AutoBotEngine.isSwingManageEnabled)
            sendResponse(exchange, 200, statusJson.toString())
        }
    }

    // 5. AutoBot Toggle Handler
    class AutoBotToggleHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            if (exchange.requestMethod.uppercase() == "POST") {
                try {
                    val bodyStream = exchange.requestBody
                    val bodyStr = bodyStream.bufferedReader().use { it.readText() }
                    if (bodyStr.isNotEmpty()) {
                        val reqJson = JSONObject(bodyStr)
                        if (reqJson.has("isEnabled")) {
                            AutoBotEngine.isEnabled = reqJson.getBoolean("isEnabled")
                            println("Remote command received: AutoBot isEnabled = ${AutoBotEngine.isEnabled}")
                        }
                        if (reqJson.has("isSwingManageEnabled")) {
                            AutoBotEngine.isSwingManageEnabled = reqJson.getBoolean("isSwingManageEnabled")
                            println("Remote command received: AutoBot isSwingManageEnabled = ${AutoBotEngine.isSwingManageEnabled}")
                        }
                    }
                    val statusJson = JSONObject()
                    statusJson.put("isEnabled", AutoBotEngine.isEnabled)
                    statusJson.put("isSwingManageEnabled", AutoBotEngine.isSwingManageEnabled)
                    statusJson.put("message", "AutoBot status updated")
                    sendResponse(exchange, 200, statusJson.toString())
                } catch (e: Exception) {
                    val err = JSONObject()
                    err.put("error", "Failed to update AutoBot status")
                    sendResponse(exchange, 400, err.toString())
                }
            } else {
                sendResponse(exchange, 405, "Method Not Allowed")
            }
        }
    }

    // 6. EOD Learning Report Handler
    class LearningReportHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val reportJsonStr = LearnedWeights.getReport()
            sendResponse(exchange, 200, reportJsonStr)
        }
    }

    // 7. Manual EOD Learning Trigger Handler
    class LearningTriggerHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            Thread {
                println("Manual EOD scan triggered via API...")
                SelfLearningEngine.runAnalysis()
            }.start()
            
            val statusJson = JSONObject()
            statusJson.put("status", "STARTED")
            statusJson.put("message", "EOD Self-Learning Analysis started in background. Check server console for progress.")
            sendResponse(exchange, 200, statusJson.toString())
        }
    }

    // 8. Live Logs Endpoint — returns last 200 log lines for remote diagnosis
    class LogsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            // Convert deque to a regular list first — deque.takeLast(n) takes no args in Kotlin
            val allLines: List<String> = logBuffer.toList()
            val lines = if (allLines.size > 200) allLines.subList(allLines.size - 200, allLines.size) else allLines
            val arr = JSONArray()
            for (line in lines) arr.put(line)
            val response = JSONObject()
            response.put("count", lines.size as Int)
            response.put("lines", arr)
            sendResponse(exchange, 200, response.toString())
        }
    }

    private fun computeSignals(): JSONArray {
        // Fetch Nifty proxy daily candles
        var niftyCandles: List<Candle> = emptyList()
        if (AngelOneClient.isLoggedIn) {
            try {
                println("BackgroundScanner: Fetching Nifty proxy daily candles (token=10576 NSE)...")
                val fetchResult = kotlinx.coroutines.runBlocking {
                    AngelOneClient.fetchHistoricalCandles(
                        symbolToken = "10576",
                        symbol = "NIFTYBEES-EQ",
                        interval = "ONE_DAY",
                        limitDays = 300
                    )
                }
                if (fetchResult is com.mobatrade.core.model.FetchResult.Success) {
                    niftyCandles = fetchResult.data
                }
                Thread.sleep(1000)
            } catch (e: Exception) {
                System.err.println("BackgroundScanner: Failed to fetch Nifty daily candles: ${e.message}")
            }
        }

        val targetDate = niftyCandles.lastOrNull()?.timestamp?.atZone(ZoneId.of("Asia/Kolkata"))?.toLocalDate() ?: LocalDate.now()
        val niftyIdx = niftyCandles.indexOfLast { it.timestamp.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate() == targetDate }

        if (niftyCandles.isEmpty() || niftyIdx == -1) {
            println("BackgroundScanner: Nifty candles are empty or target date not found. Returning empty signals.")
            return JSONArray()
        }

        // Dynamically build the list of stocks to scan from watchlist/halal_stocks cache
        val activeStocks = ArrayList<Triple<String, String, Double>>()
        val symbolToToken = mutableMapOf<String, String>()
        val symbolToDailyBias = mutableMapOf<String, String>()
        val symbolToDailyAtr = mutableMapOf<String, Double>()

        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            var cacheFile = if (isWindows) File("c:\\moba trade\\watchlist_intraday.json") else File("watchlist_intraday.json")
            if (!cacheFile.exists() || cacheFile.length() <= 2) {
                cacheFile = if (isWindows) File("c:\\moba trade\\halal_stocks.json") else File("halal_stocks.json")
            }
            if (cacheFile.exists()) {
                val content = cacheFile.readText(StandardCharsets.UTF_8)
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val symbol = obj.optString("symbol").uppercase()
                    val sector = obj.optString("sector", "IT").uppercase()
                    val rawToken = obj.optString("token")
                    
                    val token = TokenIntegrityGuard.verifyAndGetToken(symbol, rawToken) ?: rawToken

                    if (symbol.isNotEmpty() && token.isNotEmpty()) {
                        symbolToToken[symbol] = token
                        val dailyAtr = obj.optDouble("dailyAtr", 0.0)
                        val dailyBias = obj.optString("dailyBias", "UNKNOWN")
                        symbolToDailyAtr[symbol] = dailyAtr
                        symbolToDailyBias[symbol] = dailyBias
                        val defaultPrice = when (symbol) {
                            "TCS" -> 3045.00
                            "INFY" -> 1520.50
                            "WIPRO" -> 460.25
                            "RELIANCE" -> 2450.00
                            "HCLTECH" -> 1300.00
                            else -> 1000.00
                        }
                        activeStocks.add(Triple(symbol, sector, defaultPrice))
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("computeSignals: Failed to load dynamic active stocks list: ${e.message}")
        }

        ScanTelemetry.reset(activeStocks.size)

        if (activeStocks.isEmpty()) {
            activeStocks.addAll(listOf(
                Triple("TCS", "IT", 3045.00),
                Triple("INFY", "IT", 1520.50),
                Triple("WIPRO", "IT", 460.25),
                Triple("RELIANCE", "ENERGY", 2450.0),
                Triple("HCLTECH", "IT", 1300.00)
            ))
            val fallbackSymbols = listOf("TCS", "INFY", "WIPRO", "RELIANCE", "HCLTECH")
            for (sym in fallbackSymbols) {
                val token = TokenIntegrityGuard.verifyAndGetToken(sym, null)
                if (token != null) {
                    symbolToToken[sym] = token
                }
            }
        }

        // Fetch daily candles for all active stocks
        val stockCandlesMap = mutableMapOf<String, List<Candle>>()
        for ((symbol, sector, startPrice) in activeStocks) {
            var candles: List<Candle> = emptyList()
            val token = symbolToToken[symbol.uppercase()]

            if (AngelOneClient.isLoggedIn && token != null) {
                try {
                    println("BackgroundScanner: Fetching daily candles for $symbol ($token)...")
                    val fetchResult = kotlinx.coroutines.runBlocking {
                        AngelOneClient.fetchHistoricalCandles(
                            symbolToken = token,
                            symbol = symbol,
                            interval = "ONE_DAY",
                            limitDays = 300
                        )
                    }
                    candles = if (fetchResult is com.mobatrade.core.model.FetchResult.Success) fetchResult.data else emptyList()
                    Thread.sleep(350)
                } catch (e: Exception) {
                    System.err.println("BackgroundScanner: Failed to fetch daily candles for $symbol: ${e.message}")
                }
            }
            stockCandlesMap[symbol] = candles
        }

        // Compute custom RS scores
        val rsScores = mutableMapOf<String, Double>()
        val niftyPrices = niftyCandles.subList(0, niftyIdx + 1).map { it.close }
        for ((symbol, _, _) in activeStocks) {
            val stockCandles = stockCandlesMap[symbol] ?: continue
            val stockIdx = stockCandles.indexOfLast { it.timestamp.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate() == targetDate }
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

        val signalsListTemp = ArrayList<JSONObject>()

        for ((symbol, sector, startPrice) in activeStocks) {
            val candles = stockCandlesMap[symbol] ?: emptyList()
            val token = symbolToToken[symbol.uppercase()]
            val rsPercentile = percentileRanks[symbol]
            val rsScore = rsScores[symbol] ?: 0.0

            if (candles.isEmpty() || rsPercentile == null) {
                println("BackgroundScanner: No candle history or RS score for $symbol. Returning neutral 0 score.")
                val item = JSONObject()
                item.put("symbol", symbol)
                item.put("token", token)
                item.put("score", 0)
                item.put("direction", "HOLD")
                item.put("regime", "RANGING")
                item.put("compliant", com.mobatrade.core.halal.ShariahFilter.isCompliantSymbol(symbol))
                item.put("isSwingEligible", false)
                item.put("atr14", 0.0)
                item.put("triggers", JSONArray(listOf("FAILED_TO_FETCH_MARKET_DATA")))
                item.put("price", String.format("₹%,.2f", startPrice))
                item.put("dailyBias", symbolToDailyBias[symbol.uppercase()] ?: "UNKNOWN")
                item.put("dailyAtr", symbolToDailyAtr[symbol.uppercase()] ?: 0.0)
                item.put("relativeStrength", 0.0)
                item.put("rsOutperforming", false)
                item.put("rsPercentile", 0.0)
                item.put("vcpWidth", 0.0)
                item.put("sweepDepthAtr", 0.0)
                signalsListTemp.add(item)
                SelfHealingWatchlist.recordScore(symbol, 0)
                continue
            }

            // Screen using TrendTemplateScreener
            val res = TrendTemplateScreener.screen(
                symbol = symbol,
                targetDate = targetDate,
                stockCandles = candles,
                niftyCandles = niftyCandles,
                minRsScore = 15.0,
                rsPercentile = rsPercentile,
                requireVcp = true,
                maxVcpPriceRangePct = 5.0,
                minVcpVolumeContractionPct = 15.0,
                requirePullback = false,
                requireNiftyStage2 = false,
                requireLiquiditySweep = true,
                telemetryCollector = object : com.mobatrade.core.strategies.tier4.TelemetryCollector {
                    override fun recordStage2Pass() { ScanTelemetry.stage2++ }
                    override fun recordRsPass() { ScanTelemetry.rsHigh++ }
                    override fun recordVcpPass() { ScanTelemetry.vcpTight++ }
                    override fun recordSweepPass() { ScanTelemetry.sweep++ }
                }
            )

            val currentPrice = candles.last().close
            val stockIdx = candles.indexOfFirst { it.timestamp.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate() == targetDate }
            val atr = if (stockIdx != -1) calculateATR14(candles, stockIdx) else 0.0

            val item = JSONObject()
            item.put("symbol", symbol)
            item.put("token", token)
            item.put("compliant", com.mobatrade.core.halal.ShariahFilter.isCompliantSymbol(symbol))
            
            val db = symbolToDailyBias[symbol.uppercase()] ?: "UNKNOWN"

            if (res.isTriggered && com.mobatrade.core.halal.ShariahFilter.isCompliantSymbol(symbol)) {
                val stop = res.price - 2.0 * atr
                val targetPrice = res.price + 3.5 * atr
                ScanTelemetry.triggered++
                
                item.put("score", 5)
                item.put("direction", "BUY")
                item.put("regime", res.niftyRegime.name)
                item.put("isSwingEligible", true)
                item.put("atr14", atr)
                item.put("dailyAtr", atr)
                item.put("isOrb", false)
                item.put("isVwapReclaim", false)
                item.put("price", String.format("₹%,.2f", res.price))
                item.put("triggers", JSONArray(listOf(res.details)))
                
                // Expose stop and target parameters in standard keys for RiskManager/AutoBotEngine
                item.put("orbStopLoss", stop)
                item.put("orbTarget", targetPrice)
                item.put("vwapReclaimStopLoss", stop)
                item.put("vwapReclaimTarget", targetPrice)
                
                SelfHealingWatchlist.recordScore(symbol, 5)
            } else {
                item.put("score", 0)
                item.put("direction", "HOLD")
                item.put("regime", res.niftyRegime.name)
                item.put("isSwingEligible", false)
                item.put("atr14", atr)
                item.put("dailyAtr", atr)
                item.put("isOrb", false)
                item.put("isVwapReclaim", false)
                item.put("price", String.format("₹%,.2f", currentPrice))
                item.put("triggers", JSONArray(listOf("Trend template or Sweep setup not triggered")))
                SelfHealingWatchlist.recordScore(symbol, 0)
            }

            item.put("dailyBias", db)
            item.put("relativeStrength", rsScore)
            item.put("rsOutperforming", rsPercentile >= 85.0)

            item.put("rsPercentile", rsPercentile)
            item.put("vcpWidth", res.vcpWidth)
            val sweepDepth = if (stockIdx > 0 && atr > 0.0) {
                val yesterdayLow = candles[stockIdx - 1].low
                val todayLow = candles[stockIdx].low
                (yesterdayLow - todayLow) / atr
            } else {
                0.0
            }
            item.put("sweepDepthAtr", sweepDepth)

            signalsListTemp.add(item)
        }

        // Check for stale stocks and heal the watchlist
        SelfHealingWatchlist.checkAndHeal()

        // Prioritize: sort by score desc, then by rsOutperforming desc, then by relativeStrength magnitude desc
        signalsListTemp.sortWith(compareByDescending<JSONObject> { it.optInt("score", 0) }
            .thenByDescending { it.optBoolean("rsOutperforming", false) }
            .thenByDescending { it.optDouble("relativeStrength", 0.0) })

        val signalsArray = JSONArray()
        for (item in signalsListTemp) {
            signalsArray.put(item)
        }

        return signalsArray
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

    // 9. Statistics Handler
    class StatisticsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod.uppercase() == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                return
            }
            val stats = StatisticsAnalyzer.calculateStats()
            sendResponse(exchange, 200, stats.toString())
        }
    }
}

object ScanTelemetry {
    @Volatile var lastScanTime: String = "NEVER"
    @Volatile var scanned: Int = 0
    @Volatile var stage2: Int = 0
    @Volatile var rsHigh: Int = 0
    @Volatile var vcpTight: Int = 0
    @Volatile var sweep: Int = 0
    @Volatile var triggered: Int = 0

    @Synchronized
    fun reset(total: Int) {
        lastScanTime = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        scanned = total
        stage2 = 0
        rsHigh = 0
        vcpTight = 0
        sweep = 0
        triggered = 0
    }
}
