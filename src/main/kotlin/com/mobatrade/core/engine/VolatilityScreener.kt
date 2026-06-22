package com.mobatrade.core.engine

import com.mobatrade.core.model.FetchResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

object VolatilityScreener {

    @JvmStatic
    fun main(args: Array<String>) {
        println("======================================================================")
        println("      MOBA TRADE // VOLATILITY & SWING SCREENER                       ")
        println("======================================================================")

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
            System.err.println("❌ Failed to create Angel One session. Exiting.")
            return
        }
        println("✅ Connected successfully!")

        val inputFile = File("c:\\moba trade\\halal_stocks.json")
        if (!inputFile.exists()) {
            System.err.println("❌ Could not find halal_stocks.json. Exiting.")
            return
        }

        val jsonArray = JSONArray(inputFile.readText())
        val watchlist = mutableListOf<Triple<String, String, String>>() // Symbol, Token, Sector

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val symbol = obj.optString("symbol")
            val token = obj.optString("token")
            val sector = obj.optString("sector", "UNKNOWN")
            if (symbol.isNotEmpty() && token.isNotEmpty()) {
                watchlist.add(Triple(symbol, token, sector))
            }
        }

        println("📋 Loaded ${watchlist.size} stocks to scan for volatility.")
        println("⚙️ Criteria: Minimum Price ₹50, ATR(14) >= 2.5% of Price")

        val volatileStocks = mutableListOf<JSONObject>()

        for ((index, stock) in watchlist.withIndex()) {
            val (symbol, token, sector) = stock
            
            val result = runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = token,
                    symbol = symbol,
                    interval = "ONE_DAY",
                    limitDays = 60
                )
            }

            if (result is FetchResult.Success && result.data.size >= 15) {
                val candles = result.data
                val lastClose = candles.last().close
                
                if (lastClose >= 50.0) { // Avoid penny stocks
                    // Compute ATR(14)
                    val period = 14
                    val trList = mutableListOf<Double>()
                    for (i in 1 until candles.size) {
                        val current = candles[i]
                        val prev = candles[i - 1]
                        val tr = max(
                            current.high - current.low,
                            max(abs(current.high - prev.close), abs(current.low - prev.close))
                        )
                        trList.add(tr)
                    }
                    
                    if (trList.size >= period) {
                        // Simple Moving Average of TR for ATR
                        val recentTrs = trList.takeLast(period)
                        val atr = recentTrs.average()
                        val atrPct = (atr / lastClose) * 100.0

                        if (atrPct >= 2.5) {
                            println("[${index+1}/${watchlist.size}] ✅ $symbol | Price: ₹${String.format("%.2f", lastClose)} | ATR: ${String.format("%.2f", atrPct)}%")
                            val outObj = JSONObject()
                            outObj.put("symbol", symbol)
                            outObj.put("token", token)
                            outObj.put("sector", sector)
                            outObj.put("atr_pct", atrPct)
                            volatileStocks.add(outObj)
                        } else {
                            println("[${index+1}/${watchlist.size}] ❌ $symbol | Too slow (ATR ${String.format("%.2f", atrPct)}%)")
                        }
                    } else {
                        println("[${index+1}/${watchlist.size}] ⚠️ $symbol | Not enough data for ATR")
                    }
                } else {
                    println("[${index+1}/${watchlist.size}] ❌ $symbol | Price too low (₹${lastClose})")
                }
            } else {
                println("[${index+1}/${watchlist.size}] ⚠️ $symbol | Failed to fetch data or insufficient candles.")
            }

            Thread.sleep(1000) // Respect rate limits (1 request per second)
        }

        // Sort by most volatile first
        volatileStocks.sortByDescending { it.getDouble("atr_pct") }

        val outArray = JSONArray()
        volatileStocks.forEach { outArray.put(it) }

        val outFile = File("c:\\moba trade\\volatile_swing_stocks.json")
        outFile.writeText(outArray.toString(2))

        println("\n======================================================================")
        println("✅ Finished! Found ${volatileStocks.size} fast-moving stocks out of ${watchlist.size}.")
        println("💾 Saved to: ${outFile.absolutePath}")
        println("======================================================================")
    }
}
