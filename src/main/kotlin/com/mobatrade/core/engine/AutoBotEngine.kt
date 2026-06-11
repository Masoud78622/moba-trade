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
        println("🤖 [SCAN CYCLE] isEnabled=$isEnabled | isLoggedIn=${AngelOneClient.isLoggedIn} | Capital=₹$totalCapital")
        if (totalCapital <= 0) {
            System.err.println("🤖 [SCAN CYCLE] Aborting: fetchMarginCapital returned 0. Is broker connected?")
            return
        }
        val activePositionsJson = AngelOneClient.fetchActivePositions()
        
        // 1. 3:15 PM Intraday Auto-Liquidator
        val nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"))
        val isSquaringOffTime = nowIst.hour > 15 || (nowIst.hour == 15 && nowIst.minute >= 15)

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

        // 2. Drawdown Halt Check — use totalCapital as the basis, consistent with evaluateAndSizeTrade
        if (riskManager.getDailyPnL() <= -(totalCapital * 0.03)) {
            System.err.println("🤖 AUTO-TRADING HALTED: Daily drawdown limit breached. DailyPnL=₹${riskManager.getDailyPnL()}")
            return
        }

        // 3. Evaluate Swing Holdings — trailing stop + take profit management
        if (isSwingManageEnabled) {
            val swingHoldings = AngelOneClient.fetchSwingHoldings()
            var swingLiquidatedAny = false

            for (h in swingHoldings) {
                val symbol = extractSymbol(h)
                val entry = extractEntryPrice(h)
                val qty = extractQty(h)
                val token = h.optString("symboltoken", null) ?: h.optString("token", "")
                val current = AngelOneClient.fetchRealLtp(symbol, token)

                if (entry <= 0 || qty <= 0 || current <= 0) continue

                val pnlPercent = ((current - entry) / entry) * 100.0
                println("🤖 [SWING] $symbol | Entry=₹$entry | LTP=₹$current | PnL=${String.format("%.2f", pnlPercent)}%")

                when {
                    // Hard stop loss at -5%
                    pnlPercent <= -5.0 -> {
                        println("🤖 SWING STOP-LOSS: $symbol at ${String.format("%.2f", pnlPercent)}%. Liquidating all $qty shares...")
                        if (liquidatePosition(symbol, token, qty)) {
                            riskManager.closePosition(symbol, current)
                            swingLiquidatedAny = true
                        }
                    }
                    // Full take-profit at +15%
                    pnlPercent >= 15.0 -> {
                        println("🤖 SWING TARGET HIT: $symbol at +${String.format("%.2f", pnlPercent)}%. Taking full profit on $qty shares...")
                        if (liquidatePosition(symbol, token, qty)) {
                            riskManager.closePosition(symbol, current)
                            swingLiquidatedAny = true
                        }
                    }
                    // Partial exit (50%) at +10% — book half, let rest run
                    pnlPercent >= 10.0 && qty >= 2 -> {
                        val halfQty = qty / 2
                        val lastPartial = liquidatedCooldown["${symbol}_PARTIAL"] ?: 0L
                        // Only do partial exit once (cooldown 24h so we don't re-trigger each cycle)
                        if (System.currentTimeMillis() - lastPartial > 24 * 60 * 60 * 1000L) {
                            println("🤖 SWING PARTIAL EXIT: $symbol at +${String.format("%.2f", pnlPercent)}%. Booking $halfQty of $qty shares...")
                            if (liquidatePosition(symbol, token, halfQty)) {
                                liquidatedCooldown["${symbol}_PARTIAL"] = System.currentTimeMillis()
                            }
                        }
                    }
                    // Trailing stop: if we're up >= 5%, trail stop at (current high - 5%)
                    pnlPercent >= 5.0 -> {
                        val trailingStop = current * 0.95 // 5% trailing stop
                        val entryStop = entry * 0.97     // Never let a winner go below -3% from entry
                        val effectiveStop = maxOf(trailingStop, entryStop)
                        if (current <= effectiveStop) {
                            println("🤖 SWING TRAIL STOP: $symbol trailed stop hit at ₹$current (stop=₹${String.format("%.2f", effectiveStop)}). Exiting $qty shares...")
                            if (liquidatePosition(symbol, token, qty)) {
                                riskManager.closePosition(symbol, current)
                                swingLiquidatedAny = true
                            }
                        } else {
                            println("🤖 [SWING] $symbol trailing stop active at ₹${String.format("%.2f", effectiveStop)}. Price safe.")
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
        println("🤖 [SCAN CYCLE] Active positions: ${activeTickers.size}/3 — $activeTickers")
        if (activeTickers.size >= 3) {
            println("🤖 [SCAN CYCLE] Skipping new entries: max 3 positions already held.")
            return // Max 3 positions
        }
        
        val swingHoldings = AngelOneClient.fetchSwingHoldings()
        val holdingTickers = swingHoldings.map { extractSymbol(it).uppercase() }.toSet()

        val liveSignalsArray = JSONArray(MobaTradeServer.getCachedSignalsJson())
        println("🤖 [SCAN CYCLE] Evaluating ${liveSignalsArray.length()} live signals from cache...")
        for (i in 0 until liveSignalsArray.length()) {
            val sig = liveSignalsArray.getJSONObject(i)
            val symbol = sig.optString("symbol", "").uppercase()
            val token = sig.optString("token", "")
            val score = sig.optInt("score", 0)
            val compliant = sig.optBoolean("compliant", false)
            val priceStr = sig.optString("price", "₹0.00").replace("₹", "").replace(",", "")
            val price = priceStr.toDoubleOrNull() ?: 0.0
            val regime = sig.optString("regime", "UNKNOWN")

            println("🤖 [SIGNAL] $symbol | score=$score | compliant=$compliant | price=₹$price | regime=$regime")

            if (!compliant) { println("  └─ SKIP: Not Shariah-compliant."); continue }
            if (score < 3) { println("  └─ SKIP: Score $score < 3 threshold."); continue }
            if (price <= 0.0) { println("  └─ SKIP: Invalid price $price."); continue }

            if (activeTickers.contains(symbol) || holdingTickers.contains(symbol)) {
                println("  └─ SKIP: Already holding $symbol.")
                continue
            }

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
                    // Register position in RiskManager so position limits and PnL tracking work
                    riskManager.registerPosition(
                        com.mobatrade.core.model.Position(
                            symbol = symbol,
                            entryPrice = price,
                            quantity = order.quantity,
                            direction = com.mobatrade.core.model.Direction.BUY,
                            stopLoss = price * 0.98,
                            target = price * 1.05,
                            entryTime = java.time.Instant.now()
                        )
                    )
                    break // Place only one order per cycle
                } else {
                    System.err.println("  └─ ORDER REJECTED by broker for $symbol. Check AngelOneClient logs.")
                }
            }
        }
        println("🤖 [SCAN CYCLE] Complete.")
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
