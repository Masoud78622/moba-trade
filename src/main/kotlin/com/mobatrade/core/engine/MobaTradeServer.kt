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

        server.executor = null // Creates a default executor
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
                val majorStocks = listOf(
                    Triple("TCS", "IT", 3045.00),
                    Triple("INFY", "IT", 1520.50),
                    Triple("WIPRO", "IT", 460.25),
                    Triple("RELIANCE", "ENERGY", 2450.0),
                    Triple("HCLTECH", "IT", 1300.00)
                )

                val signalsArray = JSONArray()

                for ((symbol, sector, startPrice) in majorStocks) {
                    // Generate 100 realistic candles for active trend-following strategies
                    val candles = MarketDataService.generateSyntheticData(
                        regime = MarketRegime.TRENDING_BULLISH,
                        candleCount = 100,
                        startPrice = startPrice
                    )

                    val scorer = ConfluenceScorer(symbol, sector)
                    val scored = scorer.scoreTrade(candles)

                    val item = JSONObject()
                    item.put("symbol", scored.symbol)
                    item.put("score", scored.totalScore)
                    item.put("direction", scored.recommendedDirection.name)
                    item.put("regime", scored.marketRegime.name)
                    item.put("compliant", scored.isShariahCompliant)
                    
                    val triggersArray = JSONArray()
                    for (trigger in scored.triggers) {
                        triggersArray.put(trigger)
                    }
                    item.put("triggers", triggersArray)
                    item.put("price", String.format("₹%,.2f", startPrice))
                    
                    signalsArray.put(item)
                }

                sendResponse(exchange, 200, signalsArray.toString())
            } catch (e: Exception) {
                e.printStackTrace()
                val err = JSONObject()
                err.put("error", "Failed to compute signals")
                err.put("details", e.message)
                sendResponse(exchange, 500, err.toString())
            }
        }
    }
}
