package com.mobatrade.core.engine

import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.Position
import java.time.LocalDate

class RiskManager(
    private val maxDailyDrawdownPercent: Double = EnvLoader.get("MAX_DAILY_DRAWDOWN_PERCENT")?.toDoubleOrNull() ?: 3.0,
    private val maxConcurrentPositions: Int = EnvLoader.get("MAX_CONCURRENT_POSITIONS")?.toIntOrNull() ?: 2,
    private val rewardToRiskRatio: Double = EnvLoader.get("REWARD_TO_RISK_RATIO")?.toDoubleOrNull() ?: 2.0,
    private val riskPercent: Double = EnvLoader.get("RISK_PERCENT")?.toDoubleOrNull() ?: 1.0,
    private val maxAllocationPercent: Double = EnvLoader.get("MAX_ALLOCATION_PERCENT")?.toDoubleOrNull() ?: 25.0
) {
    private val activePositions = HashMap<String, Position>()
    private var dailyPnL = 0.0
    private var lastTradingDay: LocalDate = LocalDate.now()

    @Synchronized
    fun getActivePositions(): List<Position> = activePositions.values.toList()

    @Synchronized
    fun getDailyPnL(): Double = dailyPnL

    /**
     * Resets daily stats if we are on a new calendar day.
     */
    @Synchronized
    private fun checkNewDay() {
        val today = LocalDate.now()
        if (today != lastTradingDay) {
            dailyPnL = 0.0
            lastTradingDay = today
        }
    }

    /**
     * Evaluates a trade proposal and applies risk filters.
     * Computes the exact share quantity using professional risk-based position sizing.
     * All limits are dynamic — calculated as a % of availableCash so expensive stocks
     * like RELIANCE (₹2450) are never accidentally blocked by hardcoded rupee limits.
     *
     * @param symbol The ticker symbol
     * @param score Confluence score (0-10)
     * @param entryPrice Proposed entry price
     * @param stopLoss Proposed stop loss price
     * @param availableCash Real-time available margin from Angel One
     * @return Order if approved by risk controls, or null if rejected.
     */
    @Synchronized
    fun evaluateAndSizeTrade(
        symbol: String,
        score: Int,
        entryPrice: Double,
        atr14: Double,
        availableCash: Double,
        fallbackStopLoss: Double? = null,
        isSwing: Boolean = false
    ): Order? {
        checkNewDay()

        if (availableCash <= 0.0) {
            System.err.println("[RISK HALT] Available cash is zero or negative. Trading halted.")
            return null
        }

        // 1. Drawdown Halt: Check if daily loss exceeds % drawdown limit
        val maxDailyDrawdownAmt = availableCash * (maxDailyDrawdownPercent / 100.0)
        if (dailyPnL <= -maxDailyDrawdownAmt) {
            System.err.println("[RISK HALT] Daily drawdown limit exceeded ($dailyPnL). Trading is halted.")
            return null
        }

        // 2. Exposure Limit: Check concurrent positions
        if (activePositions.size >= maxConcurrentPositions) {
            System.err.println("[RISK FILTER] Maximum concurrent positions ($maxConcurrentPositions) reached. Trade skipped.")
            return null
        }

        // 3. Duplicate Position check
        if (activePositions.containsKey(symbol)) {
            System.err.println("[RISK FILTER] Position already exists for $symbol. Trade skipped.")
            return null
        }

        // 4. Calculate Risk-based Position Sizing using ATR (total capital risk based on riskPercent)
        val riskRupees = availableCash * (riskPercent / 100.0)
        
        // Stop distance uses ATR-based stop loss if isSwing is true,
        // otherwise uses fallback/custom stop loss (e.g. ORB or VWAP Reclaim) if provided,
        // otherwise for our promoted Scenario 8 Confluence setup, we use 1.0% of entryPrice
        val stopDistance = if (isSwing) {
            val atr = if (atr14 > 0.0) atr14 else (entryPrice * 0.05) // Fallback if ATR is missing
            2.0 * atr
        } else if (fallbackStopLoss != null && fallbackStopLoss < entryPrice && fallbackStopLoss > 0.0) {
            entryPrice - fallbackStopLoss
        } else {
            entryPrice * 0.01 // Scenario 8 standard 1.0% stop distance
        }

        val calculatedStopLoss = entryPrice - stopDistance
        if (calculatedStopLoss >= entryPrice || calculatedStopLoss <= 0.0) {
            System.err.println("[RISK FILTER] Invalid calculated stop loss ($calculatedStopLoss) relative to entry price ($entryPrice).")
            return null
        }

        // Hard cap: deploy max Allocation percent of capital per position
        val maxAlloc = availableCash * (maxAllocationPercent / 100.0)

        // Quantity limited by: (a) max allocation, (b) 1% capital risk size
        val capQty = (maxAlloc / entryPrice).toInt()
        val riskQty = (riskRupees / stopDistance).toInt()
        val targetQuantity = minOf(capQty, riskQty)

        if (targetQuantity <= 0) {
            System.err.println("[RISK FILTER] Calculated trade quantity is zero for $symbol @ ₹$entryPrice. Cash=₹$availableCash. Skipping.")
            return null
        }

        val totalCost = targetQuantity * entryPrice

        // Safety check: never spend more than we have
        if (totalCost > availableCash) {
            val affordableQty = (availableCash / entryPrice).toInt()
            if (affordableQty <= 0) {
                System.err.println("[RISK FILTER] Cannot afford even 1 share of $symbol @ ₹$entryPrice. Cash=₹$availableCash.")
                return null
            }
            // Use what we can afford
            val projectedTarget2 = if (isSwing) {
                val atr = if (atr14 > 0.0) atr14 else (entryPrice * 0.05)
                entryPrice + 3.5 * atr
            } else {
                entryPrice + (stopDistance * rewardToRiskRatio)
            }
            println("[RISK APPROVED] ${if (isSwing) "SWING " else ""}$symbol: Qty $affordableQty (affordability-capped), Entry ₹$entryPrice, Stop ₹$calculatedStopLoss, Target ₹$projectedTarget2, Cost ₹${affordableQty * entryPrice}")
            return Order(
                symbol = symbol,
                quantity = affordableQty,
                price = entryPrice,
                direction = Direction.BUY,
                orderType = "MARKET",
                stopLoss = calculatedStopLoss,
                target = projectedTarget2
            )
        }

        val projectedLoss = targetQuantity * stopDistance
        val projectedTarget = if (isSwing) {
            val atr = if (atr14 > 0.0) atr14 else (entryPrice * 0.05)
            entryPrice + 3.5 * atr
        } else {
            entryPrice + (stopDistance * rewardToRiskRatio)
        }

        println("[RISK APPROVED] ${if (isSwing) "SWING " else ""}$symbol: Qty $targetQuantity, Entry ₹$entryPrice, Stop ₹$calculatedStopLoss, Target ₹$projectedTarget, Cost ₹$totalCost, Risk ₹$projectedLoss")

        return Order(
            symbol = symbol,
            quantity = targetQuantity,
            price = entryPrice,
            direction = Direction.BUY,
            orderType = "MARKET",
            stopLoss = calculatedStopLoss,
            target = projectedTarget
        )
    }

    /**
     * Synchronizes the in-memory active positions map with the broker's active symbols.
     * Preserves local properties (like `isSwing`) for existing positions, removes closed ones,
     * and registers new ones with default values.
     */
    @Synchronized
    fun syncState(brokerActiveSymbols: Set<String>, realDailyPnL: Double, getPositionDetails: (String) -> Pair<Double, Int>) {
        checkNewDay()
        
        // 1. Remove positions no longer present in the broker
        val iterator = activePositions.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in brokerActiveSymbols) {
                iterator.remove()
            }
        }

        // 2. Add or update active positions from the broker
        for (symbol in brokerActiveSymbols) {
            val (entryPrice, qty) = getPositionDetails(symbol)
            val existing = activePositions[symbol]
            if (existing == null) {
                // Register new position
                activePositions[symbol] = Position(
                    symbol = symbol,
                    entryPrice = entryPrice,
                    quantity = qty,
                    direction = Direction.BUY,
                    stopLoss = entryPrice * 0.98,
                    target = entryPrice * (1.0 + (0.02 * rewardToRiskRatio)),
                    entryTime = java.time.Instant.now(),
                    isSwing = false, // Default to false for untracked positions
                    initialRiskPerShare = entryPrice * 0.02
                )
            } else {
                // Update quantity or entry price if they changed (e.g. partial exit or averaging)
                if (existing.quantity != qty || existing.entryPrice != entryPrice) {
                    activePositions[symbol] = existing.copy(
                        quantity = qty,
                        entryPrice = entryPrice
                    )
                }
            }
        }

        dailyPnL = realDailyPnL
    }

    /**
     * Registers an open position.
     */
    @Synchronized
    fun registerPosition(position: Position) {
        activePositions[position.symbol] = position
    }

    /**
     * Handles closing out a position and records profit/loss.
     * 
     * @param symbol The symbol of the closed position
     * @param exitPrice The final exit price achieved
     */
    @Synchronized
    fun closePosition(symbol: String, exitPrice: Double): Double {
        val position = activePositions.remove(symbol) ?: return 0.0
        val pnl = if (position.direction == Direction.BUY) {
            (exitPrice - position.entryPrice) * position.quantity
        } else {
            0.0 // strictly buying/long-only
        }
        
        dailyPnL += pnl
        println("[POSITION CLOSED] $symbol: Entry ${position.entryPrice}, Exit $exitPrice, Profit/Loss: ₹$pnl, Daily PnL: ₹$dailyPnL")
        return pnl
    }
}
