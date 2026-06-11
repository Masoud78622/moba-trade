package com.mobatrade.core.engine

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order

object AutoBotEngine {
    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var isSwingManageEnabled: Boolean = true

    private val riskManager = RiskManager()
    private var botThread: Thread? = null
    
    // Prevents duplicate sell orders when the broker API response is lagging
    private val liquidatedCooldown = ConcurrentHashMap<String, Long>()

    fun start() {
        if (botThread != null && botThread!!.isAlive) return
        botThread = Thread {
            while (true) {
                try {
                    if (isEnabled && AngelOneClient.ensureAuthenticated()) {
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
        
        val activePositionsJson = AngelOneClient.fetchActivePositions()
        val realActivePositions = activePositionsJson.filter { extractQty(it) > 0 }
        val realActiveSymbols = realActivePositions.map { extractSymbol(it).uppercase() }.toSet()

        // 1. Calculate the daily PnL dynamically from the broker position book (survives container restarts)
        var calculatedDailyPnL = 0.0
        for (pos in activePositionsJson) {
            val qty = extractQty(pos)
            val entry = extractEntryPrice(pos)
            val symbol = extractSymbol(pos)
            val token = pos.optString("symboltoken", null) ?: pos.optString("token", "")
            val realized = pos.optDouble("realised", 0.0)
            
            if (qty == 0) {
                calculatedDailyPnL += realized
            } else {
                val current = AngelOneClient.fetchRealLtp(symbol, token)
                if (current > 0 && entry > 0) {
                    calculatedDailyPnL += realized + (current - entry) * qty
                } else {
                    val unrealized = pos.optDouble("unrealised", 0.0)
                    calculatedDailyPnL += realized + unrealized
                }
            }
        }

        // 2. Synchronize RiskManager's active positions map and PnL with the broker
        riskManager.syncState(
            brokerActiveSymbols = realActiveSymbols,
            realDailyPnL = calculatedDailyPnL,
            getPositionDetails = { sym ->
                val p = realActivePositions.find { extractSymbol(it).uppercase() == sym }
                val entry = p?.let { extractEntryPrice(it) } ?: 0.0
                val qty = p?.let { extractQty(it) } ?: 0
                Pair(entry, qty)
            }
        )
        
        // 3. 3:15 PM Intraday Auto-Liquidator (skipping swing trades)
        val nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"))
        val isSquaringOffTime = nowIst.hour > 15 || (nowIst.hour == 15 && nowIst.minute >= 15)

        if (isSquaringOffTime) {
            for (pos in realActivePositions) {
                val symbol = extractSymbol(pos)
                val qty = extractQty(pos)
                val token = pos.optString("symboltoken", null) ?: pos.optString("token", "")
                
                val isSwingTrade = riskManager.getActivePositions().find { it.symbol == symbol }?.isSwing ?: false
                
                if (qty > 0 && !isSwingTrade) {
                    println("🤖 AUTO-BOT: 3:15 PM SQUARING OFF DAY TRADE: $symbol")
                    liquidatePosition(symbol, token, qty)
                }
            }
        }

        // 4. Drawdown Halt Check — use totalCapital as the basis, consistent with evaluateAndSizeTrade
        val capitalBasis = if (totalCapital > 0) totalCapital else 10000.0
        if (riskManager.getDailyPnL() <= -(capitalBasis * 0.03)) {
            System.err.println("🤖 AUTO-TRADING HALTED: Daily drawdown limit breached. DailyPnL=₹${riskManager.getDailyPnL()}")
            return
        }

        // 5. Evaluate Swing Holdings — trailing stop + take profit management
        if (isSwingManageEnabled) {
            val swingHoldings = AngelOneClient.fetchSwingHoldings()
            val realSwingHoldings = swingHoldings.filter { extractQty(it) > 0 }

            for (h in realSwingHoldings) {
                val symbol = extractSymbol(h)
                val entry = extractEntryPrice(h)
                val qty = extractQty(h)
                val token = h.optString("symboltoken", null) ?: h.optString("token", "")
                val current = AngelOneClient.fetchRealLtp(symbol, token)

                if (entry <= 0 || qty <= 0 || current <= 0) continue

                val pnlPercent = ((current - entry) / entry) * 100.0
                println("🤖 [SWING] $symbol | Entry=₹$entry | LTP=₹$current | PnL=${String.format("%.2f", pnlPercent)}%")

                // Update peak tracker for trailing stop
                val peak = SwingPeakTracker.updateAndGetPeak(symbol, entry, current)
                val peakGainPercent = ((peak - entry) / entry) * 100.0

                // A. Hard stop loss at -5%
                if (pnlPercent <= -5.0) {
                    println("🤖 SWING STOP-LOSS: $symbol at ${String.format("%.2f", pnlPercent)}%. Liquidating all $qty shares...")
                    if (liquidatePosition(symbol, token, qty)) {
                        riskManager.closePosition(symbol, current)
                        SwingPeakTracker.clear(symbol)
                    }
                    continue
                }

                // B. Full take-profit at +15%
                if (pnlPercent >= 15.0) {
                    println("🤖 SWING TARGET HIT: $symbol at +${String.format("%.2f", pnlPercent)}%. Taking full profit on $qty shares...")
                    if (liquidatePosition(symbol, token, qty)) {
                        riskManager.closePosition(symbol, current)
                        SwingPeakTracker.clear(symbol)
                    }
                    continue
                }

                // C. Trailing stop check: if peak gain >= 5%, trailing stop is activated
                if (peakGainPercent >= 5.0) {
                    val trailingStop = peak * 0.95 // 5% trailing stop below peak
                    val entryStop = entry * 0.97     // Never let a winner go below -3% from entry
                    val effectiveStop = maxOf(trailingStop, entryStop)
                    
                    if (current <= effectiveStop) {
                        println("🤖 SWING TRAIL STOP: $symbol trailed stop hit at ₹$current (peak=₹$peak, stop=₹${String.format("%.2f", effectiveStop)}). Exiting all $qty shares...")
                        if (liquidatePosition(symbol, token, qty)) {
                            riskManager.closePosition(symbol, current)
                            SwingPeakTracker.clear(symbol)
                        }
                        continue
                    } else {
                        println("🤖 [SWING] $symbol trailing stop active. Peak=₹$peak | Stop=₹${String.format("%.2f", effectiveStop)} | Price safe.")
                    }
                }

                // D. Partial exit (50%) at +10% — book half, let rest run
                if (pnlPercent >= 10.0 && qty >= 2) {
                    val halfQty = qty / 2
                    val lastPartial = liquidatedCooldown["${symbol}_PARTIAL"] ?: 0L
                    if (System.currentTimeMillis() - lastPartial > 24 * 60 * 60 * 1000L) {
                        println("🤖 SWING PARTIAL EXIT: $symbol at +${String.format("%.2f", pnlPercent)}%. Booking $halfQty of $qty shares...")
                        if (liquidatePosition(symbol, token, halfQty)) {
                            liquidatedCooldown["${symbol}_PARTIAL"] = System.currentTimeMillis()
                        }
                    }
                }
            }
        }

        // 6. Evaluate Active Day Trades for SL/TP (skipping swing trades)
        for (pos in realActivePositions) {
            val symbol = extractSymbol(pos)
            val entry = extractEntryPrice(pos)
            val qty = extractQty(pos)
            val token = pos.optString("symboltoken", null) ?: pos.optString("token", "")
            
            val isSwingTrade = riskManager.getActivePositions().find { it.symbol == symbol }?.isSwing ?: false
            if (isSwingTrade) continue

            val current = AngelOneClient.fetchRealLtp(symbol, token)

            if (qty > 0 && entry > 0 && current > 0) {
                val sl = entry * 0.98 // 2% SL
                val target = entry * 1.05 // 5% Target

                if (current <= sl) {
                    println("🤖 AUTO-BOT: Intraday position $symbol triggered SL. Liquidating...")
                    liquidatePosition(symbol, token, qty)
                } else if (current >= target) {
                    println("🤖 AUTO-BOT: Intraday position $symbol triggered Target. Liquidating...")
                    liquidatePosition(symbol, token, qty)
                }
            }
        }

        // Do NOT buy new day trades if it's past 3:15 PM
        if (isSquaringOffTime) return

        // 7. Evaluate Live Signals for Entry
        val activeTickers = realActiveSymbols
        val swingHoldings = AngelOneClient.fetchSwingHoldings()
        val holdingTickers = swingHoldings.filter { extractQty(it) > 0 }.map { extractSymbol(it).uppercase() }.toSet()

        println("🤖 [SCAN CYCLE] Active positions: ${activeTickers.size}/3 — $activeTickers")
        if (activeTickers.size >= 3) {
            println("🤖 [SCAN CYCLE] Skipping new entries: max 3 positions already held.")
            return // Max 3 positions
        }

        if (totalCapital <= 0) {
            println("🤖 [SCAN CYCLE] Skipping new entries: available capital is ₹$totalCapital <= 0.")
            return
        }

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
            val isSwingEligible = sig.optBoolean("isSwingEligible", false)

            println("🤖 [SIGNAL] $symbol | score=$score | compliant=$compliant | price=₹$price | regime=$regime | isSwingEligible=$isSwingEligible")

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
                    riskManager.registerPosition(
                        com.mobatrade.core.model.Position(
                            symbol = symbol,
                            entryPrice = price,
                            quantity = order.quantity,
                            direction = com.mobatrade.core.model.Direction.BUY,
                            stopLoss = price * 0.98,
                            target = price * 1.05,
                            entryTime = java.time.Instant.now(),
                            isSwing = isSwingEligible
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

object SwingPeakTracker {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val FILE_PATH = if (isWindows) "c:\\moba trade\\swing_peaks.json" else "swing_peaks.json"
    
    private val peaks = ConcurrentHashMap<String, Double>()

    init {
        load()
    }

    @Synchronized
    fun load() {
        try {
            val file = File(FILE_PATH)
            if (file.exists()) {
                val content = file.readText()
                if (content.isNotEmpty()) {
                    val json = JSONObject(content)
                    for (key in json.keys()) {
                        peaks[key] = json.getDouble(key)
                    }
                }
            }
        } catch (e: java.lang.Exception) {
            System.err.println("SwingPeakTracker: Failed to load peaks: ${e.message}")
        }
    }

    @Synchronized
    fun save() {
        try {
            val json = JSONObject()
            for ((key, value) in peaks) {
                json.put(key, value)
            }
            File(FILE_PATH).writeText(json.toString())
        } catch (e: java.lang.Exception) {
            System.err.println("SwingPeakTracker: Failed to save peaks: ${e.message}")
        }
    }

    private fun fetchHistoricalPeak(symbol: String, entryPrice: Double): Double {
        try {
            val token = TokenIntegrityGuard.verifyAndGetToken(symbol, null) ?: return entryPrice
            val candles = AngelOneClient.fetchHistoricalCandles(token, symbol, "ONE_DAY", 15)
            if (candles.isNotEmpty()) {
                val maxHigh = candles.filter { it.high >= entryPrice }.map { it.high }.maxOrNull()
                if (maxHigh != null) {
                    println("SwingPeakTracker: Reconstructed historical peak for $symbol: ₹$maxHigh (Entry: ₹$entryPrice)")
                    return maxHigh
                }
            }
        } catch (e: Exception) {
            System.err.println("SwingPeakTracker: Failed to fetch historical peak for $symbol: ${e.message}")
        }
        return entryPrice
    }

    @Synchronized
    fun updateAndGetPeak(symbol: String, entryPrice: Double, currentPrice: Double): Double {
        val symbolKey = symbol.uppercase()
        val currentPeak = peaks[symbolKey]
        val newPeak = if (currentPeak == null) {
            val histPeak = fetchHistoricalPeak(symbolKey, entryPrice)
            maxOf(entryPrice, currentPrice, histPeak)
        } else {
            maxOf(currentPeak, currentPrice)
        }
        peaks[symbolKey] = newPeak
        save()
        return newPeak
    }

    @Synchronized
    fun clear(symbol: String) {
        peaks.remove(symbol.uppercase())
        save()
    }
}
