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

object MobaTradeServer {
    @Volatile
    private var cachedSignalsJson: String = "[]"

    fun getCachedSignalsJson(): String = cachedSignalsJson

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

        // Seed News Sentiment and Sector Rotation scores for high confluence signals
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("TCS", 0.85)
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("INFY", 0.82)
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("WIPRO", 0.78)
        com.mobatrade.core.strategies.tier5.NewsSentiment.updateSentiment("HCLTECH", 0.81)

        com.mobatrade.core.strategies.tier4.SectorRotation.updateSectorScores(
            mapOf("IT" to 1.12, "PHARMA" to 1.08, "FMCG" to 1.02)
        )

        // Set up context handlers
        server.createContext("/", HomeHandler())
        server.createContext("/status", StatusHandler())
        server.createContext("/halal-stocks", HalalStocksHandler())
        server.createContext("/signals", SignalsHandler())
        server.createContext("/autobot/status", AutoBotStatusHandler())
        server.createContext("/autobot/toggle", AutoBotToggleHandler())
        server.createContext("/learning/report", LearningReportHandler())
        server.createContext("/learning/trigger", LearningTriggerHandler())

        server.executor = null // Creates a default executor
        
        // Start AutoBot Engine (dormant until toggled)
        AutoBotEngine.start()
        
        // Start EOD Self-Learning Engine (daemon)
        SelfLearningEngine.start()

        // Holdings Verification Module Initialization
        println("Initializing Token Integrity Guard...")
        TokenIntegrityGuard.ensureMasterDownloaded()

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
                val clientId = System.getenv("ANGEL_CLIENT_ID") ?: AngelOneClient.DEFAULT_CLIENT_ID
                val apiKey = System.getenv("ANGEL_API_KEY") ?: AngelOneClient.DEFAULT_API_KEY
                val pin = System.getenv("ANGEL_PIN") ?: "3112"
                val totpSecret = System.getenv("ANGEL_TOTP_SECRET") ?: AngelOneClient.DEFAULT_TOTP_SECRET
                println("Server startup: attempting Angel One auto-login...")
                val success = AngelOneClient.login(
                    clientId = clientId,
                    tradingPassword = pin,
                    apiKey = apiKey,
                    totpSecret = totpSecret
                )
                if (success) {
                    println("Server startup: Angel One login successful. brokerConnected = true")
                } else {
                    System.err.println("Server startup: Angel One login failed. Check ANGEL_CLIENT_ID, ANGEL_API_KEY, ANGEL_PIN, and ANGEL_TOTP_SECRET env vars.")
                }
            } catch (e: Exception) {
                System.err.println("Server startup: login thread exception: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()

        // Start background scanner thread to update signals cache without blocking HTTP endpoints
        Thread {
            while (true) {
                try {
                    if (AngelOneClient.isLoggedIn) {
                        println("BackgroundScanner: Starting scheduled scan of stock universe...")
                        val signalsArray = computeSignals()
                        cachedSignalsJson = signalsArray.toString()
                        println("BackgroundScanner: Successfully updated signals cache with ${signalsArray.length()} items.")
                        
                        // Sleep 5 minutes between full scans
                        Thread.sleep(5 * 60 * 1000)
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
                welcomeJson.put("version", "1.0.0")
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
            statusJson.put("serverTime", Instant.now().toString())

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

    private fun computeSignals(): JSONArray {
        // Dynamically build the list of stocks to scan from halal_stocks.json cache
        val activeStocks = ArrayList<Triple<String, String, Double>>()
        val symbolToToken = mutableMapOf<String, String>()

        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cacheFile = if (isWindows) File("c:\\moba trade\\halal_stocks.json") else File("halal_stocks.json")
            if (cacheFile.exists()) {
                val content = cacheFile.readText(StandardCharsets.UTF_8)
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val symbol = obj.optString("symbol").uppercase()
                    val sector = obj.optString("sector", "IT").uppercase()
                    val rawToken = obj.optString("token")
                    
                    // Centralized source of truth:
                    val token = TokenIntegrityGuard.verifyAndGetToken(symbol, rawToken) ?: rawToken

                    if (symbol.isNotEmpty() && token.isNotEmpty()) {
                        symbolToToken[symbol] = token
                        // Provide reasonable default start prices if falling back to synthetic
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

        // If loading from cache failed or returned nothing, fall back to our benchmark 5 stocks
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

        val signalsArray = JSONArray()

        for ((symbol, sector, startPrice) in activeStocks) {
            var candles: List<Candle> = emptyList()
            val token = symbolToToken[symbol.uppercase()]

            if (AngelOneClient.isLoggedIn && token != null) {
                try {
                    println("BackgroundScanner: Fetching real-world hourly candles for $symbol ($token)...")
                    candles = AngelOneClient.fetchHistoricalCandles(
                        symbolToken = token,
                        symbol = symbol,
                        interval = "ONE_HOUR",
                        limitDays = 15
                    )
                    // Rate limit protection: sleep for 3000ms to respect Angel One SmartAPI guidelines
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    System.err.println("BackgroundScanner: Failed to fetch real-world candles for $symbol: ${e.message}")
                }
            }

            // SAFE FAIL: Do not use synthetic data if API fails. Return a neutral 0-score signal.
            if (candles.isEmpty()) {
                println("BackgroundScanner: No real-world candles available for $symbol. Returning neutral 0 score to avoid fake trades.")
                val item = JSONObject()
                item.put("symbol", symbol)
                item.put("token", token)
                item.put("score", 0)
                item.put("direction", "HOLD")
                item.put("regime", "RANGING")
                item.put("compliant", com.mobatrade.core.halal.ShariahFilter.isCompliantSymbol(symbol))
                item.put("isSwingEligible", false)
                item.put("triggers", JSONArray(listOf("FAILED_TO_FETCH_MARKET_DATA")))
                item.put("price", String.format("₹%,.2f", startPrice))
                signalsArray.put(item)
                continue
            }

            val scorer = ConfluenceScorer(symbol, sector)
            val scored = scorer.scoreTrade(candles)

            val item = JSONObject()
            item.put("symbol", scored.symbol)
            item.put("token", token)
            item.put("score", scored.totalScore)
            item.put("direction", scored.recommendedDirection.name)
            item.put("regime", scored.marketRegime.name)
            item.put("compliant", scored.isShariahCompliant)
            item.put("isSwingEligible", scored.isSwingEligible)
            
            val triggersArray = JSONArray()
            for (trigger in scored.triggers) {
                triggersArray.put(trigger)
            }
            item.put("triggers", triggersArray)
            
            // Return the actual last close price as the stock's current price in the signals JSON.
            val currentPrice = if (candles.isNotEmpty()) candles.last().close else startPrice
            item.put("price", String.format("₹%,.2f", currentPrice))
            
            signalsArray.put(item)
        }

        return signalsArray
    }
}
