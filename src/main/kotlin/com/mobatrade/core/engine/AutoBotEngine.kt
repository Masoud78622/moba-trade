package com.mobatrade.core.engine

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDate
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.model.FetchResult

object AutoBotEngine {
    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var isSwingManageEnabled: Boolean = true

    val riskManager = RiskManager()
    private var botThread: Thread? = null
    
    // Prevents duplicate sell orders when the broker API response is lagging
    private val liquidatedCooldown = ConcurrentHashMap<String, Long>()

    private val symbolToAtrPct = ConcurrentHashMap<String, Double>()
    
    @Volatile
    private var lastSwingCheckSlot: String = ""

    private fun loadAtrPctMap() {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val file = if (isWindows) File("c:\\moba trade\\volatile_swing_stocks.json") else File("volatile_swing_stocks.json")
            if (file.exists()) {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val symbol = obj.optString("symbol").uppercase()
                    val atrPct = obj.optDouble("atr_pct", 0.0)
                    if (symbol.isNotEmpty() && atrPct > 0.0) {
                        symbolToAtrPct[symbol] = atrPct
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("AutoBotEngine: Failed to load symbolToAtrPct map: ${e.message}")
        }
    }

    fun start() {
        loadAtrPctMap()
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
        val isPaperTrading = EnvLoader.get("PAPER_TRADING")?.toBoolean() ?: true

        // 0. Load paper trading state at start of cycle
        if (isPaperTrading) {
            val paperPositions = PaperTradingStorage.loadPositions()
            val paperSymbols = paperPositions.map { it.optString("symbol").uppercase() }.toSet()
            
            riskManager.syncState(
                brokerActiveSymbols = paperSymbols,
                realDailyPnL = 0.0,
                getPositionDetails = { sym ->
                    val p = paperPositions.find { it.optString("symbol").uppercase() == sym }
                    val entry = p?.optDouble("buyavgprice") ?: 0.0
                    val qty = p?.optInt("qty") ?: 0
                    Pair(entry, qty)
                }
            )
            // Re-apply specific saved stopLoss, target, firstPartialDone, etc.
            for (pObj in paperPositions) {
                val sym = pObj.optString("symbol").uppercase()
                val activePos = riskManager.getActivePositions().find { it.symbol == sym }
                if (activePos != null) {
                    activePos.stopLoss = pObj.optDouble("stopLoss", activePos.stopLoss)
                    activePos.target = pObj.optDouble("target", activePos.target)
                    val isSwingVal = pObj.optBoolean("isSwing", activePos.isSwing)
                    try {
                        val f = activePos.javaClass.getDeclaredField("isSwing")
                        f.isAccessible = true
                        f.set(activePos, isSwingVal)
                    } catch (e: Exception) {}
                    val entryTimeStr = pObj.optString("entryTime", "")
                    if (entryTimeStr.isNotEmpty()) {
                        try {
                            val f = activePos.javaClass.getDeclaredField("entryTime")
                            f.isAccessible = true
                            f.set(activePos, java.time.Instant.parse(entryTimeStr))
                        } catch (e: Exception) {}
                    }
                    activePos.firstPartialDone = pObj.optBoolean("firstPartialDone", false)
                    activePos.secondPartialDone = pObj.optBoolean("secondPartialDone", false)
                    activePos.atr14 = pObj.optDouble("atr14", activePos.atr14)
                    activePos.highestClose = pObj.optDouble("highestClose", activePos.highestClose)
                    activePos.initialRiskPerShare = pObj.optDouble("initialRiskPerShare", activePos.initialRiskPerShare)
                }
            }
        }

        val totalCapital = if (isPaperTrading) {
            PaperTradingStorage.loadCapital()
        } else {
            AngelOneClient.fetchMarginCapital()
        }
        
        println("🤖 [SCAN CYCLE] isEnabled=$isEnabled | isLoggedIn=${AngelOneClient.isLoggedIn} | Capital=₹$totalCapital | Mode=${if (isPaperTrading) "PAPER_TRADING" else "LIVE_TRADING"}")

        // Clear any stale ORB fired-today entries from yesterday
        OpeningRangeEngine.clearStaleEntries()
        
        val activePositionsJson = if (isPaperTrading) {
            PaperTradingStorage.loadPositions()
        } else {
            AngelOneClient.fetchActivePositions()
        }
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
                val current = if (AngelOneClient.isLoggedIn) AngelOneClient.fetchRealLtp(symbol, token) else entry
                if (current > 0 && entry > 0) {
                    calculatedDailyPnL += realized + (current - entry) * qty
                } else {
                    val unrealized = pos.optDouble("unrealised", 0.0)
                    calculatedDailyPnL += realized + unrealized
                }
            }
        }

        // 2. Synchronize RiskManager's active positions map and PnL
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

        // If paper trading, re-apply the saved fields again (after syncState)
        if (isPaperTrading) {
            val paperPositions = PaperTradingStorage.loadPositions()
            for (pObj in paperPositions) {
                val sym = pObj.optString("symbol").uppercase()
                val activePos = riskManager.getActivePositions().find { it.symbol == sym }
                if (activePos != null) {
                    activePos.stopLoss = pObj.optDouble("stopLoss", activePos.stopLoss)
                    activePos.target = pObj.optDouble("target", activePos.target)
                    val isSwingVal = pObj.optBoolean("isSwing", activePos.isSwing)
                    try {
                        val f = activePos.javaClass.getDeclaredField("isSwing")
                        f.isAccessible = true
                        f.set(activePos, isSwingVal)
                    } catch (e: Exception) {}
                    val entryTimeStr = pObj.optString("entryTime", "")
                    if (entryTimeStr.isNotEmpty()) {
                        try {
                            val f = activePos.javaClass.getDeclaredField("entryTime")
                            f.isAccessible = true
                            f.set(activePos, java.time.Instant.parse(entryTimeStr))
                        } catch (e: Exception) {}
                    }
                    activePos.firstPartialDone = pObj.optBoolean("firstPartialDone", false)
                    activePos.secondPartialDone = pObj.optBoolean("secondPartialDone", false)
                    activePos.atr14 = pObj.optDouble("atr14", activePos.atr14)
                    activePos.highestClose = pObj.optDouble("highestClose", activePos.highestClose)
                    activePos.initialRiskPerShare = pObj.optDouble("initialRiskPerShare", activePos.initialRiskPerShare)
                }
            }
        }
        
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

        // 5. Evaluate Swing Holdings — Hourly 1-Hour candle checks strictly at candle closes
        if (isSwingManageEnabled) {
            val nowIst = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"))
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
            
            // Check if current slot is one of: 10:15, 11:15, 12:15, 13:15, 14:15, 15:15 IST
            val hourlySlots = setOf(10, 11, 12, 13, 14, 15)
            val currentSlot = "${today}_${nowIst.hour}"
            
            val isCheckMinute = nowIst.minute >= 15 && nowIst.minute <= 20 // 5-minute buffer window at the close
            val shouldRunCheck = nowIst.hour in hourlySlots && isCheckMinute && currentSlot != lastSwingCheckSlot

            if (shouldRunCheck) {
                println("🤖 [AUTO-BOT] RUNNING HOURLY SWING CHECK FOR SLOT $currentSlot AT ${nowIst} IST...")
                loadAtrPctMap() // Refresh ATR map
                
                val swingHoldings = AngelOneClient.fetchSwingHoldings()
                val realSwingHoldings = swingHoldings.filter { extractQty(it) > 0 }

                for (h in realSwingHoldings) {
                    val symbol = extractSymbol(h).uppercase()
                    val entry = extractEntryPrice(h)
                    val qty = extractQty(h)
                    val token = h.optString("symboltoken", null) ?: h.optString("token", "")
                    
                    if (entry <= 0 || qty <= 0 || token.isEmpty()) continue

                    // Fetch most recent ONE_HOUR candles to check exit triggers
                    println("📡 [SWING CHECK] Fetching 1-hour candles for $symbol...")
                    val result = kotlinx.coroutines.runBlocking {
                        AngelOneClient.fetchHistoricalCandles(
                            symbolToken = token,
                            symbol = symbol,
                            interval = "ONE_HOUR",
                            limitDays = 5 // Fetch last few days to guarantee recent candles
                        )
                    }

                    if (result is FetchResult.Success && result.data.isNotEmpty()) {
                        val candles = result.data
                        val lastCandle = candles.last()
                        
                        val high = lastCandle.high
                        val low = lastCandle.low
                        val close = lastCandle.close

                        val isPaper = EnvLoader.get("PAPER_TRADING")?.toBoolean() ?: true
                        
                        var stopLoss = 0.0
                        var target = 0.0
                        var daysHeld = 0
                        var atrVal = 0.0
                        
                        if (isPaper) {
                            val paperPositions = PaperTradingStorage.loadPositions()
                            val pObj = paperPositions.find { it.optString("symbol").uppercase() == symbol }
                            if (pObj != null) {
                                stopLoss = pObj.optDouble("stopLoss", 0.0)
                                target = pObj.optDouble("target", 0.0)
                                atrVal = pObj.optDouble("atr14", 0.0)
                                val entryTimeStr = pObj.optString("entryTime", "").split("T")[0]
                                if (entryTimeStr.isNotEmpty()) {
                                    daysHeld = getTradingDaysHeld(symbol, LocalDate.parse(entryTimeStr), token)
                                }
                            }
                        } else {
                            val meta = SwingPositionMetadataStore.getMetadata(symbol)
                            if (meta != null) {
                                stopLoss = meta.optDouble("stopLoss", 0.0)
                                target = meta.optDouble("target", 0.0)
                                atrVal = meta.optDouble("atr", 0.0)
                                val entryDateStr = meta.optString("entryDate", "")
                                if (entryDateStr.isNotEmpty()) {
                                    daysHeld = getTradingDaysHeld(symbol, LocalDate.parse(entryDateStr), token)
                                }
                            }
                        }

                        // Fallback if metadata is missing (e.g. manually placed trades or before update)
                        if (stopLoss <= 0.0 || target <= 0.0) {
                            val atrPct = symbolToAtrPct[symbol] ?: 5.0
                            val atr = entry * (atrPct / 100.0)
                            stopLoss = entry - 2.0 * atr
                            target = entry + 3.5 * atr
                            atrVal = atr
                        }

                        println("🤖 [SWING CHECK] $symbol | Entry=₹$entry | 1h Candle (High=₹$high, Low=₹$low, Close=₹$close) | SL=₹$stopLoss | TP=₹$target | ATR=${String.format("%.2f", atrVal)} | Days Held: $daysHeld")

                        // Evaluate exits based on the 1-hour candle high/low
                        var liquidated = false

                        // A. Stop Loss (Conservative: priority if both hit)
                        if (low <= stopLoss) {
                            println("🤖 SWING STOP-LOSS HIT: 1h low ₹$low <= SL ₹$stopLoss for $symbol. Liquidating all $qty shares...")
                            if (liquidatePosition(symbol, token, qty)) {
                                riskManager.closePosition(symbol, stopLoss)
                                if (!isPaper) SwingPositionMetadataStore.removeMetadata(symbol)
                                liquidated = true
                            }
                        }

                        // B. Take Profit (Target)
                        if (!liquidated && high >= target) {
                            println("🤖 SWING TARGET HIT: 1h high ₹$high >= TP ₹$target for $symbol. Taking profit on $qty shares...")
                            if (liquidatePosition(symbol, token, qty)) {
                                riskManager.closePosition(symbol, target)
                                if (!isPaper) SwingPositionMetadataStore.removeMetadata(symbol)
                                liquidated = true
                            }
                        }

                        // C. 10-Day Time Exit
                        if (!liquidated && daysHeld >= 10) {
                            println("🤖 SWING TIME LIMIT REACHED: $symbol held for $daysHeld trading days. Liquidating at close price ₹$close...")
                            if (liquidatePosition(symbol, token, qty)) {
                                riskManager.closePosition(symbol, close)
                                if (!isPaper) SwingPositionMetadataStore.removeMetadata(symbol)
                                liquidated = true
                            }
                        }
                    } else {
                        System.err.println("⚠️ [SWING CHECK] Failed to fetch 1-hour candles for $symbol: ${if (result is FetchResult.Failure) result.reason else "Empty"}")
                    }
                    Thread.sleep(350) // Respect rate limits
                }
                
                // Mark slot as processed
                lastSwingCheckSlot = currentSlot
                println("✅ [AUTO-BOT] Finished swing check for slot $currentSlot.")
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

        // Check Intraday Session Gates (9:30-12:00 and 13:30-15:00)
        if (!isEntryWindowOpen()) {
            println("🤖 [SCAN CYCLE] Skipping new entries: Outside primary entry windows.")
            return
        }

        // Fixed score threshold = 3 (Nifty index gate removed)
        val scoreThreshold = 3
        println("🤖 [SCAN CYCLE] Score threshold is fixed at $scoreThreshold.")

        val rawSignalsArray = JSONArray(MobaTradeServer.getCachedSignalsJson())
        val liveSignalsList = mutableListOf<JSONObject>()
        for (i in 0 until rawSignalsArray.length()) {
            liveSignalsList.add(rawSignalsArray.getJSONObject(i))
        }
        // Prioritize by: score desc, then by rsOutperforming desc, then by relativeStrength magnitude desc
        liveSignalsList.sortWith(compareByDescending<JSONObject> { it.optInt("score", 0) }
            .thenByDescending { it.optBoolean("rsOutperforming", false) }
            .thenByDescending { it.optDouble("relativeStrength", 0.0) })

        println("🤖 [SCAN CYCLE] Evaluating ${liveSignalsList.size} live signals from cache (sorted by score and relative strength)...")
        for (sig in liveSignalsList) {
            val symbol = sig.optString("symbol", "").uppercase()
            val token = sig.optString("token", "")
            val score = sig.optInt("score", 0)
            val compliant = sig.optBoolean("compliant", false)
            val priceStr = sig.optString("price", "₹0.00").replace("₹", "").replace(",", "")
            val price = priceStr.toDoubleOrNull() ?: 0.0
            val regime = sig.optString("regime", "UNKNOWN")
            val isSwingEligible = sig.optBoolean("isSwingEligible", false)
            val relativeStrength = sig.optDouble("relativeStrength", 0.0)
            val rsOutperforming = sig.optBoolean("rsOutperforming", false)

            println("🤖 [SIGNAL] $symbol | score=$score | compliant=$compliant | price=₹$price | regime=$regime | isSwingEligible=$isSwingEligible | RS=${String.format("%.4f%%", relativeStrength)} | rsOutperforming=$rsOutperforming")

            if (!compliant) { println("  └─ SKIP: Not Shariah-compliant."); continue }

            // Regime-adaptive entry threshold
            if (score < scoreThreshold) { println("  └─ SKIP: Score $score < $scoreThreshold (regime threshold)."); continue }
            if (price <= 0.0) { println("  └─ SKIP: Invalid price $price."); continue }

            if (activeTickers.contains(symbol) || holdingTickers.contains(symbol)) {
                println("  └─ SKIP: Already holding $symbol.")
                continue
            }

            val atr14 = sig.optDouble("atr14", 0.0)
            val dailyAtr = sig.optDouble("dailyAtr", 0.0)
            val atrToUse = if (dailyAtr > 0.0) dailyAtr else atr14
            val isOrb = sig.optBoolean("isOrb", false)
            val orbStopLoss = sig.optDouble("orbStopLoss", 0.0)
            val orbTarget = sig.optDouble("orbTarget", 0.0)
            val isVwapReclaim = sig.optBoolean("isVwapReclaim", false)
            val vwapReclaimStopLoss = sig.optDouble("vwapReclaimStopLoss", 0.0)
            val vwapReclaimTarget = sig.optDouble("vwapReclaimTarget", 0.0)

            val order = riskManager.evaluateAndSizeTrade(
                symbol = symbol,
                score = score,
                entryPrice = price,
                atr14 = atrToUse,
                availableCash = totalCapital,
                fallbackStopLoss = if (isOrb && orbStopLoss > 0) {
                    orbStopLoss
                } else if (isVwapReclaim && vwapReclaimStopLoss > 0) {
                    vwapReclaimStopLoss
                } else {
                    null
                },
                isSwing = isSwingEligible
            )

            if (order != null) {
                println("🤖 AUTO-BOT: LIVE ORDER INITIATED — ${order.quantity} × $symbol @ ₹${price}")
                val orderResult = if (isPaperTrading) {
                    PaperTradingStorage.executePaperBuy(order, totalCapital)
                } else {
                    kotlinx.coroutines.runBlocking { AngelOneClient.placeOrder(order, token) }
                }
                if (orderResult is com.mobatrade.core.model.OrderResult.Success) {
                    val orderId = orderResult.orderId
                    println("🤖 AUTO-BOT: ORDER SUCCESSFUL. ID: $orderId")
                    riskManager.registerPosition(
                        com.mobatrade.core.model.Position(
                            symbol = symbol,
                            entryPrice = price,
                            quantity = order.quantity,
                            direction = com.mobatrade.core.model.Direction.BUY,
                            stopLoss = if (isOrb && orbStopLoss > 0) {
                                orbStopLoss
                            } else if (isVwapReclaim && vwapReclaimStopLoss > 0) {
                                vwapReclaimStopLoss
                            } else {
                                order.stopLoss ?: (price - (atrToUse * 2.0))
                            },
                            target = if (isOrb && orbTarget > 0) {
                                orbTarget
                            } else if (isVwapReclaim && vwapReclaimTarget > 0) {
                                vwapReclaimTarget
                            } else {
                                order.target ?: (price + (atrToUse * 3.5))
                            },
                            entryTime = java.time.Instant.now(),
                            isSwing = isSwingEligible,
                            atr14 = atrToUse,
                            initialRiskPerShare = if (isOrb && orbStopLoss > 0) {
                                price - orbStopLoss
                            } else if (isVwapReclaim && vwapReclaimStopLoss > 0) {
                                price - vwapReclaimStopLoss
                            } else {
                                price - (order.stopLoss ?: (price - (atrToUse * 2.0)))
                            }
                        )
                    )
                    
                    if (isPaperTrading) {
                        PaperTradingStorage.savePositions(riskManager.getActivePositions())
                    } else {
                        val todayStr = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString()
                        val registeredPos = riskManager.getActivePositions().find { it.symbol == symbol }
                        if (registeredPos != null) {
                            SwingPositionMetadataStore.addMetadata(
                                symbol = symbol,
                                entryPrice = price,
                                atr = atrToUse,
                                stopLoss = registeredPos.stopLoss,
                                target = registeredPos.target,
                                entryDate = todayStr
                            )
                        }
                    }
                    break // Place only one order per cycle
                } else {
                    System.err.println("  └─ ORDER REJECTED by broker or paper engine for $symbol.")
                }
            }
        }
        if (isPaperTrading) {
            PaperTradingStorage.savePositions(riskManager.getActivePositions())
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
        val isPaperTrading = EnvLoader.get("PAPER_TRADING")?.toBoolean() ?: true
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
        val orderResult = if (isPaperTrading) {
            PaperTradingStorage.executePaperSell(symbol, qty)
        } else {
            kotlinx.coroutines.runBlocking { AngelOneClient.placeOrder(order, token) }
        }
        if (orderResult is com.mobatrade.core.model.OrderResult.Success) {
            liquidatedCooldown[symbol] = nowMs
            return true
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
    
    private val peaks      = ConcurrentHashMap<String, Double>()
    /** ISO date string (yyyy-MM-dd IST) of when the position was first seen. */
    private val entryDates = ConcurrentHashMap<String, String>()

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
                        if (key.endsWith("_entry_date")) {
                            entryDates[key.removeSuffix("_entry_date")] = json.getString(key)
                        } else {
                            peaks[key] = json.getDouble(key)
                        }
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
            for ((key, value) in entryDates) {
                json.put("${key}_entry_date", value)
            }
            File(FILE_PATH).writeText(json.toString())
        } catch (e: java.lang.Exception) {
            System.err.println("SwingPeakTracker: Failed to save peaks: ${e.message}")
        }
    }

    /**
     * Returns how many calendar days this symbol has been held.
     * Returns 0 if entry date is unknown.
     */
    fun getDaysHeld(symbol: String): Long {
        val dateStr = entryDates[symbol.uppercase()] ?: return 0L
        return try {
            val entry = java.time.LocalDate.parse(dateStr)
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
            java.time.temporal.ChronoUnit.DAYS.between(entry, today)
        } catch (e: Exception) { 0L }
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
            // First time we see this symbol — record entry date
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString()
            entryDates[symbolKey] = today
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
        entryDates.remove(symbol.uppercase())
        save()
    }
}

object SwingPositionMetadataStore {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val FILE_PATH = if (isWindows) File("c:\\moba trade\\swing_positions.json") else File("swing_positions.json")
    
    private val metadataMap = ConcurrentHashMap<String, JSONObject>()

    init {
        load()
    }

    @Synchronized
    fun load() {
        try {
            if (FILE_PATH.exists()) {
                val content = FILE_PATH.readText(StandardCharsets.UTF_8)
                if (content.isNotEmpty()) {
                    val array = JSONArray(content)
                    metadataMap.clear()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val symbol = obj.getString("symbol").uppercase()
                        metadataMap[symbol] = obj
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("SwingPositionMetadataStore: Failed to load: ${e.message}")
        }
    }

    @Synchronized
    fun save() {
        try {
            val array = JSONArray()
            for (obj in metadataMap.values) {
                array.put(obj)
            }
            FILE_PATH.writeText(array.toString(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("SwingPositionMetadataStore: Failed to save: ${e.message}")
        }
    }

    @Synchronized
    fun addMetadata(symbol: String, entryPrice: Double, atr: Double, stopLoss: Double, target: Double, entryDate: String) {
        val obj = JSONObject()
        obj.put("symbol", symbol.uppercase())
        obj.put("entryPrice", entryPrice)
        obj.put("atr", atr)
        obj.put("stopLoss", stopLoss)
        obj.put("target", target)
        obj.put("entryDate", entryDate)
        metadataMap[symbol.uppercase()] = obj
        save()
    }

    @Synchronized
    fun getMetadata(symbol: String): JSONObject? {
        return metadataMap[symbol.uppercase()]
    }

    @Synchronized
    fun removeMetadata(symbol: String) {
        metadataMap.remove(symbol.uppercase())
        save()
    }
}

private fun getTradingDaysHeld(symbol: String, entryDate: java.time.LocalDate, token: String): Int {
    try {
        val fetchResult = kotlinx.coroutines.runBlocking {
            AngelOneClient.fetchHistoricalCandles(
                symbolToken = token,
                symbol = symbol,
                interval = "ONE_DAY",
                limitDays = 30
            )
        }
        if (fetchResult is FetchResult.Success) {
            val candles = fetchResult.data
            val entryIdx = candles.indexOfFirst { it.timestamp.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate() == entryDate }
            if (entryIdx != -1) {
                return candles.size - 1 - entryIdx
            }
        }
    } catch (e: Exception) {
        System.err.println("Failed to calculate trading days held for $symbol: ${e.message}")
    }
    val today = java.time.LocalDate.now(ZoneId.of("Asia/Kolkata"))
    return java.time.temporal.ChronoUnit.DAYS.between(entryDate, today).toInt()
}
