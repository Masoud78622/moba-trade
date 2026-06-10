package com.mobatrade.core.engine

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order

object AutoBotEngine {
    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var isSwingManageEnabled: Boolean = false

    private val riskManager = RiskManager()
    private var botThread: Thread? = null
    
    // Prevents duplicate sell orders when the broker API response is lagging
    private val liquidatedCooldown = ConcurrentHashMap<String, Long>()

    fun start() {
        if (botThread != null && botThread!!.isAlive) return
        botThread = Thread {
            while (true) {
                try {
                    if (isEnabled && AngelOneClient.isLoggedIn) {
                        runScanCycle()
                    }
                    Thread.sleep(30_000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    System.err.println("AutoBotEngine Exception: ${e.message}")
                    try { Thread.sleep(15_000) } catch (ie: InterruptedException) { break }
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun runScanCycle() {
        val totalCapital = AngelOneClient.fetchMarginCapital()
        if (totalCapital <= 0) return

        val splitCapital = totalCapital / 2.0
        val activePositionsJson = AngelOneClient.fetchActivePositions()
        
        // 1. 3:15 PM Intraday Auto-Liquidator
        val nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"))
        val isSquaringOffTime = nowIst.hour == 15 && nowIst.minute >= 15

        if (isSquaringOffTime) {
            var liquidatedAny = false
            for (pos in activePositionsJson) {
                val symbol = extractSymbol(pos)
                val qty = extractQty(pos)
                val token = pos.optString("symboltoken", null) ?: pos.optString("token", "")
                
                if (qty > 0) {
                    println("🤖 AUTO-BOT: 3:15 PM SQUARING OFF DAY TRADE: $symbol")
                    if (liquidatePosition(symbol, token, qty)) {
                        liquidatedAny = true
                    }
                }
            }
            if (liquidatedAny) return
        }

        // 2. Drawdown Halt Check
        if (riskManager.getDailyPnL() <= -(splitCapital * 0.03)) {
            System.err.println("🤖 AUTO-TRADING HALTED: Daily drawdown limit breached.")
            return
        }

        // 3. Evaluate Swing Holdings
        if (isSwingManageEnabled) {
            val swingHoldings = AngelOneClient.fetchSwingHoldings()
            var swingLiquidatedAny = false
            val dynamicThreshold = -5.0 // 5% stop loss

            for (h in swingHoldings) {
                val symbol = extractSymbol(h)
                val entry = extractEntryPrice(h)
                val qty = extractQty(h)
                val token = h.optString("symboltoken", null) ?: h.optString("token", "")
                val current = AngelOneClient.fetchRealLtp(symbol, token)

                if (entry > 0 && qty > 0) {
                    val pnlPercent = ((current - entry) / entry) * 100.0
                    if (pnlPercent <= dynamicThreshold) {
                        println("🤖 AUTO-BOT: Swing holding $symbol breached stop-loss ($pnlPercent%). Liquidating...")
                        if (liquidatePosition(symbol, token, qty)) {
                            swingLiquidatedAny = true
                        }
                    }
                }
            }
            if (swingLiquidatedAny) return
        }

        // 4. Evaluate Active Day Trades for SL/TP
        var activeLiquidatedAny = false
        for (pos in activePositionsJson) {
            val symbol = extractSymbol(pos)
            val entry = extractEntryPrice(pos)
            val qty = extractQty(pos)
            val token = pos.optString("symboltoken", null) ?: pos.optString("token", "")
            val current = AngelOneClient.fetchRealLtp(symbol, token)

            if (qty > 0 && entry > 0) {
                val sl = entry * 0.98 // 2% SL
                val target = entry * 1.05 // 5% Target

                if (current <= sl) {
                    println("🤖 AUTO-BOT: Intraday position $symbol triggered SL. Liquidating...")
                    if (liquidatePosition(symbol, token, qty)) {
                        activeLiquidatedAny = true
                    }
                } else if (current >= target) {
                    println("🤖 AUTO-BOT: Intraday position $symbol triggered Target. Liquidating...")
                    if (liquidatePosition(symbol, token, qty)) {
                        activeLiquidatedAny = true
                    }
                }
            }
        }
        if (activeLiquidatedAny) return

        // Do NOT buy new day trades if it's past 3:15 PM
        if (isSquaringOffTime) return

        // 5. Evaluate Live Signals for Entry
        val activeTickers = activePositionsJson.map { extractSymbol(it).uppercase() }.toSet()
        if (activeTickers.size >= 3) return // Max 3 positions
        
        val swingHoldings = AngelOneClient.fetchSwingHoldings()
        val holdingTickers = swingHoldings.map { extractSymbol(it).uppercase() }.toSet()

        val liveSignalsArray = JSONArray(MobaTradeServer.getCachedSignalsJson())
        for (i in 0 until liveSignalsArray.length()) {
            val sig = liveSignalsArray.getJSONObject(i)
            val symbol = sig.optString("symbol", "").uppercase()
            val token = sig.optString("token", "")
            val score = sig.optInt("score", 0)
            val compliant = sig.optBoolean("compliant", false)
            val priceStr = sig.optString("price", "₹0.00").replace("₹", "").replace(",", "")
            val price = priceStr.toDoubleOrNull() ?: 0.0

            if (compliant && score >= 3 && price > 0.0) {
                if (activeTickers.contains(symbol) || holdingTickers.contains(symbol)) continue

                val order = riskManager.evaluateAndSizeTrade(
                    symbol = symbol,
                    score = score,
                    entryPrice = price,
                    stopLoss = price * 0.98,
                    availableCash = totalCapital
                )

                if (order != null) {
                    println("🤖 AUTO-BOT: LIVE ORDER INITIATED — ${order.quantity} × $symbol @ ₹${price}")
                    val orderId = AngelOneClient.placeOrder(order, token, isRetry = false)
                    if (orderId != null) {
                        println("🤖 AUTO-BOT: ORDER SUCCESSFUL. ID: $orderId")
                        break // Place only one order per cycle
                    }
                }
            }
        }
    }

    private fun extractSymbol(json: JSONObject): String {
        val sym = json.optString("tradingsymbol", null) ?: json.optString("symbol", "UNKNOWN")
        return sym.split("-")[0].trim().uppercase()
    }

    private fun extractQty(json: JSONObject): Int {
        val qtyStr = json.optString("netqty", null) ?: json.optString("quantity", null) ?: json.optString("qty", "0")
        return qtyStr.toDoubleOrNull()?.toInt() ?: 0
    }

    private fun extractEntryPrice(json: JSONObject): Double {
        val priceStr = json.optString("buyavgprice", null) ?: json.optString("averageprice", null) ?: json.optString("avgprice", "0")
        return priceStr.toDoubleOrNull() ?: 0.0
    }

    private fun liquidatePosition(symbol: String, token: String, qty: Int): Boolean {
        val nowMs = System.currentTimeMillis()
        val lastLiquidated = liquidatedCooldown[symbol] ?: 0L
        if (nowMs - lastLiquidated < 120_000L) { // 2 minutes cooldown
            println("🤖 AUTO-BOT: Skipping sell for $symbol to prevent duplicate orders (cooldown active).")
            return false
        }

        val order = Order(
            symbol = symbol,
            quantity = qty,
            price = 0.0,
            direction = Direction.SELL,
            orderType = "MARKET",
            stopLoss = null,
            target = null
        )
        val orderId = AngelOneClient.placeOrder(order, token, isRetry = false)
        if (orderId != null) {
            liquidatedCooldown[symbol] = nowMs
            return true
        }
        return false
    }
}
