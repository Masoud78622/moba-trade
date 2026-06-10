package com.mobatrade.core.engine

import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.Position
import java.time.LocalDate

class RiskManager(
    private val maxDailyDrawdownPercent: Double = 3.0,  // Halt if daily loss exceeds 3% of capital
    private val maxConcurrentPositions: Int = 3
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
        stopLoss: Double,
        availableCash: Double
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

        // 4. Validate Stop Loss input
        if (stopLoss >= entryPrice || stopLoss <= 0.0) {
            System.err.println("[RISK FILTER] Invalid stop loss ($stopLoss) relative to entry price ($entryPrice).")
            return null
        }

        // 5. Determine Capital Allocation % based on Confluence Score
        // All limits are dynamic % of availableCash — no hardcoded rupee caps
        val allocFraction = when {
            score >= 6 -> 0.30   // High confidence: deploy up to 30% of capital
            score >= 4 -> 0.20   // Medium confidence: deploy up to 20% of capital
            score >= 3 -> 0.10   // Low confidence: deploy up to 10% of capital
            else -> return null  // Score below 3 = NO TRADE
        }
        val maxAlloc = availableCash * allocFraction

        // 6. Risk-based position sizing: risk max 2% of capital per trade
        val maxRiskAmt = availableCash * 0.02
        val riskPerShare = entryPrice - stopLoss

        // Quantity limited by: (a) max allocation, (b) max risk per trade
        val capQty = (maxAlloc / entryPrice).toInt()
        val riskQty = if (riskPerShare > 0) (maxRiskAmt / riskPerShare).toInt() else capQty
        var targetQuantity = minOf(capQty, riskQty)

        // Ensure at least 1 share if we can afford it
        if (targetQuantity <= 0 && entryPrice <= availableCash) {
            targetQuantity = 1
        }

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
            val projectedTarget2 = entryPrice + (riskPerShare * 2.0)
            println("[RISK APPROVED] $symbol: Qty $affordableQty (affordability-capped), Entry ₹$entryPrice, Stop ₹$stopLoss, Target ₹$projectedTarget2, Cost ₹${affordableQty * entryPrice}")
            return Order(
                symbol = symbol,
                quantity = affordableQty,
                price = entryPrice,
                direction = Direction.BUY,
                orderType = "MARKET",
                stopLoss = stopLoss,
                target = projectedTarget2
            )
        }

        val projectedLoss = targetQuantity * riskPerShare
        val projectedTarget = entryPrice + (riskPerShare * 2.0)

        println("[RISK APPROVED] $symbol: Qty $targetQuantity, Entry ₹$entryPrice, Stop ₹$stopLoss, Target ₹$projectedTarget, Cost ₹$totalCost, Risk ₹$projectedLoss")

        return Order(
            symbol = symbol,
            quantity = targetQuantity,
            price = entryPrice,
            direction = Direction.BUY,
            orderType = "MARKET",
            stopLoss = stopLoss,
            target = projectedTarget
        )
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
