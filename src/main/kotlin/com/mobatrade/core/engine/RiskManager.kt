package com.mobatrade.core.engine

import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.Position
import java.time.LocalDate

class RiskManager(
    private val totalCapital: Double = 100000.0,
    private val maxStandardAllocation: Double = 20000.0, // A/B setups: ₹20K
    private val maxHalfAllocation: Double = 10000.0,     // C setups: ₹10K
    private val maxSingleTradeRisk: Double = 1500.0,      // Max ₹1.5K loss per trade (1.5% of total capital)
    private val maxDailyDrawdown: Double = 3000.0,        // Max ₹3K loss per day (3%)
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
     * 
     * @param symbol The ticker symbol
     * @param score Confluence score (0-10)
     * @param entryPrice Proposed entry price
     * @param stopLoss Proposed stop loss price
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

        // 1. Drawdown Halt: Check if daily loss exceeds drawdown limit
        if (dailyPnL <= -maxDailyDrawdown) {
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

        // 5. Determine Capital Allocation based on Confluence Score
        val maxAlloc = when {
            score >= 6 -> Math.min(maxStandardAllocation, availableCash * 0.50) // Up to 50% of available cash, capped at 20K
            score >= 4 -> Math.min(maxHalfAllocation, availableCash * 0.33)     // Up to 33% of available cash, capped at 10K
            score >= 3 -> Math.min(maxHalfAllocation, availableCash * 0.20)     // Moderate setup: 20% of cash
            else -> return null                  // Score below 3 = NO TRADE
        }

        // 6. Professional Risk-Based Position Sizing:
        // Size = min( CapitalAlloc / EntryPrice, MaxRisk / RiskPerShare )
        val riskPerShare = entryPrice - stopLoss
        
        val capQty = (maxAlloc / entryPrice).toInt()
        val riskQty = (maxSingleTradeRisk / riskPerShare).toInt()
        
        val targetQuantity = Math.min(capQty, riskQty)

        if (targetQuantity <= 0) {
            System.err.println("[RISK FILTER] Calculated trade quantity is zero. Skipping trade.")
            return null
        }

        val totalCost = targetQuantity * entryPrice
        val projectedLoss = targetQuantity * riskPerShare
        
        // 7. Double check allocation boundaries
        if (totalCost > maxAlloc * 1.05) {
            System.err.println("[RISK FILTER] Position cost ($totalCost) exceeds max allowed allocation. Adjusting...")
            return null
        }

        // Target target ratio (Reward) is typically 1:2 risk-to-reward ratio minimum
        val projectedTarget = entryPrice + (riskPerShare * 2.0)

        println("[RISK APPROVED] $symbol: Quantity $targetQuantity, Entry $entryPrice, Stop $stopLoss, Target $projectedTarget, Total Cost: ₹$totalCost, Potential Risk: ₹$projectedLoss")

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
