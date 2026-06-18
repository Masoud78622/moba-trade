package com.mobatrade.core.engine

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.Candle

object AutoBotEngine {
    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var isSwingManageEnabled: Boolean = true

    val riskManager = RiskManager()
    private var botThread: Thread? = null
    
    // Prevents duplicate sell orders when the broker API response is lagging
    private val liquidatedCooldown = ConcurrentHashMap<String, Long>()

    fun start() {
        if (botThread != null && botThread!!.isAlive) return
        botThread = Thread {
            while (true) {
                try {
                    if (isEnabled && AngelOneClient.ensureAuthenticated()) {
                        if (TokenIntegrityGuard.isReady()) {
                            runScanCycle()
                        } else {
                            println("🤖 [AUTO-BOT] Waiting for Scrip Master to finish loading...")
                        }
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

        // 6. Evaluate Active Day Trades for SL/TP and Partial Exits
        for (pos in realActivePositions) {
            val symbol = extractSymbol(pos)
            val entry = extractEntryPrice(pos)
            val qty = extractQty(pos)
            val token = pos.optString("symboltoken", null) ?: pos.optString("token", "")
            
            val activePos = riskManager.getActivePositions().find { it.symbol == symbol }
            val isSwingTrade = activePos?.isSwing ?: false
            if (isSwingTrade) continue

            val current = AngelOneClient.fetchRealLtp(symbol, token)

            if (qty > 0 && entry > 0 && current > 0 && activePos != null) {
                // Update highest close
                if (current > activePos.highestClose) {
                    activePos.highestClose = current
                }

                val effectiveR = if (activePos.initialRiskPerShare > 0) activePos.initialRiskPerShare else (activePos.entryPrice * 0.02)
                
                val target1_5 = activePos.entryPrice + (1.5 * effectiveR)
                val target2_5 = activePos.entryPrice + (2.5 * effectiveR)

                when {
                    // A. Hard stop loss or Trailing stop for remaining 20%
                    current <= activePos.stopLoss -> {
                        println("🤖 AUTO-BOT: Day trade $symbol hit Stop Loss at ₹$current. Liquidating all remaining $qty shares...")
                        if (liquidatePosition(symbol, token, qty)) {
                            riskManager.closePosition(symbol, current)
                        }
                    }

                    // B. First partial exit at 1.5R: sell 40% of position and move SL to breakeven
                    current >= target1_5 && !activePos.firstPartialDone && qty >= 2 -> {
                        val sellQty = Math.max(1, (qty * 0.40).toInt())
                        println("🤖 AUTO-BOT: Day trade $symbol reached 1.5R (₹$target1_5). Booking 40% profit (selling $sellQty shares) and moving SL to breakeven...")
                        if (liquidatePosition(symbol, token, sellQty)) {
                            activePos.firstPartialDone = true
                            activePos.stopLoss = activePos.entryPrice // Breakeven
                            riskManager.registerPosition(activePos)
                        }
                    }

                    // C. Second partial exit at 2.5R: sell 40% more
                    current >= target2_5 && !activePos.secondPartialDone && activePos.firstPartialDone && qty >= 2 -> {
                        val sellQty = Math.max(1, (qty * 0.40).toInt())
                        println("🤖 AUTO-BOT: Day trade $symbol reached 2.5R (₹$target2_5). Booking another 40% profit (selling $sellQty shares)...")
                        if (liquidatePosition(symbol, token, sellQty)) {
                            activePos.secondPartialDone = true
                            riskManager.registerPosition(activePos)
                        }
                    }

                    // D. Trailing stop check for the remaining 20%
                    activePos.firstPartialDone && activePos.atr14 > 0.0 -> {
                        val trailSL = activePos.highestClose - activePos.atr14
                        if (current <= trailSL) {
                            println("🤖 AUTO-BOT: Day trade $symbol hit trailing SL at ₹$current (highest close = ₹${activePos.highestClose}, ATR = ₹${activePos.atr14}, stop = ₹$trailSL). Liquidating remaining $qty shares...")
                            if (liquidatePosition(symbol, token, qty)) {
                                riskManager.closePosition(symbol, current)
                            }
                        }
                    }

                    // E. Hard target exit if first/second partial was not possible (qty too small)
                    current >= activePos.target && qty < 2 -> {
                        println("🤖 AUTO-BOT: Day trade $symbol reached target ₹${activePos.target} (qty 1). Liquidating...")
                        if (liquidatePosition(symbol, token, qty)) {
                            riskManager.closePosition(symbol, current)
                        }
                    }
                }
            }
        }

        // Do NOT buy new day trades if it's past 3:15 PM
        if (isSquaringOffTime) return

        // 7. Evaluate Live Signals for Entry
        val activeTickers = realActiveSymbols
        val swingHoldings = AngelOneClient.fetchSwingHoldings()
        val holdingTickers = swingHoldings.filter { extractQty(it) > 0 }.map { extractSymbol(it).uppercase() }.toSet()

        println("🤖 [SCAN CYCLE] Active day trades: ${activeTickers.size}/2 | Swing trades: ${holdingTickers.size} — $activeTickers")
        if (activeTickers.size >= 2) {
            println("🤖 [SCAN CYCLE] Skipping new entries: max 2 day trades already held.")
            return // Max 2 positions
        }

        if (totalCapital <= 0) {
            println("🤖 [SCAN CYCLE] Skipping new entries: available capital is ₹$totalCapital <= 0.")
            return
        }

        // Check Intraday Session Gates (9:45-11:30 and 14:00-15:00)
        if (!isEntryWindowOpen()) {
            println("🤖 [SCAN CYCLE] Skipping new entries: Outside primary entry windows.")
            return
        }

        val rawSignalsArray = JSONArray(MobaTradeServer.getCachedSignalsJson())
        val liveSignalsList = mutableListOf<JSONObject>()
        for (i in 0 until rawSignalsArray.length()) {
            liveSignalsList.add(rawSignalsArray.getJSONObject(i))
        }
        // Score-Based Prioritization: sort signals by conviction before evaluating
        liveSignalsList.sortByDescending { it.optInt("score", 0) }

        println("🤖 [SCAN CYCLE] Evaluating ${liveSignalsList.size} live signals from cache (sorted by score)...")
        for (sig in liveSignalsList) {
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
            
            // Entry threshold: score >= 3 (at least 3 confluence factors must align)
            if (score < 3) { println("  └─ SKIP: Score $score < 3 threshold."); continue }
            if (price <= 0.0) { println("  └─ SKIP: Invalid price $price."); continue }

            if (activeTickers.contains(symbol) || holdingTickers.contains(symbol)) {
                println("  └─ SKIP: Already holding $symbol.")
                continue
            }

            val atr14 = sig.optDouble("atr14", 0.0)
            
            val order = riskManager.evaluateAndSizeTrade(
                symbol = symbol,
                score = score,
                entryPrice = price,
                atr14 = atr14,
                availableCash = totalCapital
            )

            if (order != null) {
                println("🤖 AUTO-BOT: LIVE ORDER INITIATED — ${order.quantity} × $symbol @ ₹${price}")
                val orderResult = kotlinx.coroutines.runBlocking { AngelOneClient.placeOrder(order, token) }
                if (orderResult is com.mobatrade.core.model.OrderResult.Success) {
                    val orderId = orderResult.orderId
                    println("🤖 AUTO-BOT: ORDER SUCCESSFUL. ID: $orderId")
                    riskManager.registerPosition(
                        com.mobatrade.core.model.Position(
                            symbol = symbol,
                            entryPrice = price,
                            quantity = order.quantity,
                            direction = com.mobatrade.core.model.Direction.BUY,
                            stopLoss = order.stopLoss ?: (price - (atr14 * 1.5)),
                            target = order.target ?: (price + (atr14 * 1.5 * 2.0)),
                            entryTime = java.time.Instant.now(),
                            isSwing = isSwingEligible,
                            atr14 = atr14,
                            initialRiskPerShare = price - (order.stopLoss ?: (price - (atr14 * 1.5)))
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
        val orderResult = kotlinx.coroutines.runBlocking { AngelOneClient.placeOrder(order, token) }
        if (orderResult is com.mobatrade.core.model.OrderResult.Success) {
            liquidatedCooldown[symbol] = nowMs
            return true
        }
        return false
    }

    private fun checkNiftyRegime(): Boolean {
        if (!AngelOneClient.isLoggedIn) return false
        try {
            println("🤖 [SCAN CYCLE] Fetching Nifty 50 index candles to evaluate market regime...")
            val fetchResult = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(
                    symbolToken = "99926000",
                    symbol = "Nifty 50",
                    interval = "FIFTEEN_MINUTE",
                    limitDays = TradingConstants.CANDLE_HISTORY_DAYS_INTRADAY_SCORING
                )
            }
            val candles = if (fetchResult is com.mobatrade.core.model.FetchResult.Success) fetchResult.data else emptyList()
            if (candles.isEmpty()) {
                println("⚠️ [SCAN CYCLE] Could not fetch Nifty 50 index data. Defaulting to block entries (fail closed).")
                return false
            }
            val closePrices = candles.map { it.close }
            val ema20 = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(closePrices, 20)
            val ema50 = com.mobatrade.core.strategies.tier4.TechIndicators.calculateEma(closePrices, 50)
            
            if (ema20.isEmpty() || ema50.isEmpty()) return false
            
            val lastEma20 = ema20.last()
            val lastEma50 = ema50.last()
            val lastCandle = candles.last()
            val isBullish = lastEma20 > lastEma50
            
            println("🤖 [SCAN CYCLE] Nifty 50 EMA20 = ${String.format("%.2f", lastEma20)} | EMA50 = ${String.format("%.2f", lastEma50)} | Bullish = $isBullish")
            return isBullish
        } catch (e: Exception) {
            System.err.println("Error checking Nifty regime: ${e.message}")
        }
        return false
    }

    private fun isEntryWindowOpen(): Boolean {
        val now = LocalTime.now(ZoneId.of("Asia/Kolkata"))
        // Morning window: 9:30 AM - 12:00 PM (avoids opening 15 min spike, captures full morning trend)
        val morningStart = LocalTime.of(9, 30)
        val morningEnd = LocalTime.of(12, 0)
        // Afternoon window: 1:30 PM - 3:00 PM (post-lunch momentum, stops before square-off)
        val afternoonStart = LocalTime.of(13, 30)
        val afternoonEnd = LocalTime.of(15, 0)
        
        val inMorning = now.isAfter(morningStart) && now.isBefore(morningEnd)
        val inAfternoon = now.isAfter(afternoonStart) && now.isBefore(afternoonEnd)
        
        return inMorning || inAfternoon
    }

    private fun findPreviousSwingHigh(candles: List<Candle>): Double {
        val period = 2
        for (i in candles.size - 4 downTo period) {
            val currentHigh = candles[i].high
            var isSwingHigh = true
            for (j in 1..period) {
                if (candles[i - j].high >= currentHigh || candles[i + j].high > currentHigh) {
                    isSwingHigh = false
                    break
                }
            }
            if (isSwingHigh) {
                return currentHigh
            }
        }
        return candles.dropLast(1).maxOfOrNull { it.close } ?: 0.0
    }

    private fun calculateATR14(candles: List<Candle>): Double {
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
            val fetchResult = kotlinx.coroutines.runBlocking { AngelOneClient.fetchHistoricalCandles(token, symbol, "ONE_DAY", 15) }
            val candles = if (fetchResult is com.mobatrade.core.model.FetchResult.Success) fetchResult.data else emptyList()
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
