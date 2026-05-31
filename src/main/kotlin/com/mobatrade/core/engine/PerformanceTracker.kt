package com.mobatrade.core.engine

import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.TradeRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object PerformanceTracker {
    private val tradeHistory = ArrayList<TradeRecord>()

    data class StrategyStats(
        val strategyName: String,
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val totalPnL: Double,
        val profitFactor: Double
    )

    data class DailyReport(
        val date: LocalDate,
        val totalTrades: Int,
        val totalPnL: Double,
        val winRate: Double,
        val strategyWinRates: Map<String, Double>
    )

    @Synchronized
    fun addTradeRecord(record: TradeRecord) {
        tradeHistory.add(record)
    }

    @Synchronized
    fun getTradeHistory(): List<TradeRecord> = tradeHistory.toList()

    /**
     * Compute statistics for all registered strategies.
     */
    @Synchronized
    fun getStatsPerStrategy(): List<StrategyStats> {
        val grouped = tradeHistory.groupBy { it.strategyName }
        val statsList = ArrayList<StrategyStats>()

        for ((strategyName, trades) in grouped) {
            val total = trades.size
            val wins = trades.count { it.isWin }
            val losses = total - wins
            val winRate = if (total > 0) (wins.toDouble() / total) * 100.0 else 0.0
            val totalPnL = trades.sumOf { it.pnl }
            
            val grossProfit = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
            val grossLoss = Math.abs(trades.filter { it.pnl < 0 }.sumOf { it.pnl })
            val profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else if (grossProfit > 0.0) 99.9 else 0.0

            statsList.add(
                StrategyStats(
                    strategyName = strategyName,
                    totalTrades = total,
                    wins = wins,
                    losses = losses,
                    winRate = winRate,
                    totalPnL = totalPnL,
                    profitFactor = profitFactor
                )
            )
        }
        return statsList
    }

    /**
     * Compute the End-of-Day report for a given date.
     */
    @Synchronized
    fun generateDailyReport(date: LocalDate): DailyReport {
        val zone = ZoneId.of("Asia/Kolkata")
        val dailyTrades = tradeHistory.filter {
            it.exitTime.atZone(zone).toLocalDate() == date
        }

        val totalTrades = dailyTrades.size
        val totalPnL = dailyTrades.sumOf { it.pnl }
        val wins = dailyTrades.count { it.isWin }
        val winRate = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100.0 else 0.0

        val strategyWinRates = dailyTrades.groupBy { it.strategyName }
            .mapValues { (_, trades) ->
                val w = trades.count { it.isWin }
                (w.toDouble() / trades.size) * 100.0
            }

        return DailyReport(
            date = date,
            totalTrades = totalTrades,
            totalPnL = totalPnL,
            winRate = winRate,
            strategyWinRates = strategyWinRates
        )
    }

    /**
     * Seeds initial trading history with highly realistic mock records 
     * to demonstrate report metrics on first-time load.
     */
    fun seedMockHistory() {
        synchronized(this) {
            if (tradeHistory.isNotEmpty()) return
            
            val now = Instant.now()
            val zone = ZoneId.of("Asia/Kolkata")
            
            // Seed a variety of trades for different strategies
            val mockData = listOf(
                TradeRecord("T1", "TCS", Direction.BUY, 6, 3000.0, 3120.0, now.minusSeconds(10000), now.minusSeconds(5000), "Opening Range Breakout"),
                TradeRecord("T2", "INFY", Direction.BUY, 10, 1500.0, 1530.0, now.minusSeconds(9000), now.minusSeconds(4000), "Box Theory (Darvas Box)"),
                TradeRecord("T3", "WIPRO", Direction.BUY, 25, 450.0, 438.0, now.minusSeconds(8000), now.minusSeconds(3000), "Volume Profile (POC, VAH, VAL)"),
                TradeRecord("T4", "TCS", Direction.BUY, 6, 3050.0, 3090.0, now.minusSeconds(7000), now.minusSeconds(2000), "Order Block Detection"),
                TradeRecord("T5", "INFY", Direction.BUY, 10, 1510.0, 1490.0, now.minusSeconds(6000), now.minusSeconds(1000), "Liquidity Sweep + Reversal"),
                TradeRecord("T6", "WIPRO", Direction.BUY, 25, 442.0, 458.0, now.minusSeconds(5000), now.minusSeconds(500), "EMA Crossover (9/21/50)"),
                TradeRecord("T7", "TCS", Direction.BUY, 6, 3080.0, 3135.0, now.minusSeconds(4000), now.minusSeconds(100), "Opening Range Breakout")
            )
            
            tradeHistory.addAll(mockData)
        }
    }
}
