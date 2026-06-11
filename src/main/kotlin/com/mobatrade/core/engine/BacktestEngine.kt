package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.strategies.Strategy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class SimulatedTrade(
    val symbol: String,
    val qty: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnl: Double,
    val isWin: Boolean,
    val entryTime: String,
    val exitTime: String,
    val daysHeld: Int
)

data class BacktestResult(
    val strategyName: String,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val netProfit: Double,
    val profitFactor: Double,
    val maxDrawdownPercent: Double,
    val completedTrades: List<SimulatedTrade>,
    val equityCurve: List<Double>
)

object BacktestEngine {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Kolkata"))

    /**
     * Executes a historical simulation of a specific Strategy over a series of candles.
     */
    fun runBacktest(
        strategy: Strategy,
        candles: List<Candle>,
        startingCapital: Double = 100000.0,
        riskPerTradePercent: Double = 1.5,
        defaultStopLossPercent: Double = 1.5,
        defaultTargetPercent: Double = 3.0
    ): BacktestResult {
        if (candles.size < 50) {
            return BacktestResult(strategy.name, 0, 0, 0, 0.0, 0.0, 1.0, 0.0, emptyList(), listOf(startingCapital))
        }

        val completedTrades = ArrayList<SimulatedTrade>()
        val equityCurve = ArrayList<Double>()
        equityCurve.add(startingCapital)

        var currentCapital = startingCapital
        var activeTrade: SimulatedActiveTrade? = null

        // We start evaluation from index 40 to ensure strategy has enough historical candles
        for (i in 40 until candles.size) {
            val currentCandle = candles[i]
            
            // 1. If in active trade, check for exit conditions first
            if (activeTrade != null) {
                val trade = activeTrade
                var exited = false
                var exitPrice = 0.0
                var pnl = 0.0
                var exitTime = currentCandle.timestamp
                val daysHeld = ((i - trade.entryIndex) / 10).coerceAtLeast(1) // 10 candles ~= 1 day in 1h timeframe

                val historySlice = candles.subList(0, i)
                val exitSignal = strategy.evaluate(historySlice, null)

                if (exitSignal != null && exitSignal.direction == Direction.SELL) {
                    exited = true
                    exitPrice = currentCandle.close
                    pnl = (exitPrice - trade.entryPrice) * trade.qty
                } else if (currentCandle.low <= trade.stopLoss) {
                    // Stop Loss Triggered
                    exited = true
                    exitPrice = trade.stopLoss
                    pnl = (exitPrice - trade.entryPrice) * trade.qty
                } else if (currentCandle.high >= trade.target) {
                    // Target Triggered
                    exited = true
                    exitPrice = trade.target
                    pnl = (exitPrice - trade.entryPrice) * trade.qty
                }

                if (exited) {
                    currentCapital += pnl
                    equityCurve.add(currentCapital)
                    completedTrades.add(
                        SimulatedTrade(
                            symbol = strategy.name.take(4).uppercase(),
                            qty = trade.qty,
                            entryPrice = trade.entryPrice,
                            exitPrice = exitPrice,
                            pnl = pnl,
                            isWin = pnl > 0,
                            entryTime = formatter.format(trade.entryTime),
                            exitTime = formatter.format(exitTime),
                            daysHeld = daysHeld
                        )
                    )
                    activeTrade = null
                }
            }

            // 2. If NOT in a trade, evaluate strategy for potential BUY entries
            if (activeTrade == null) {
                // Pass history slice up to current candle (non-inclusive of future candles)
                val historySlice = candles.subList(0, i)
                val signal = strategy.evaluate(historySlice, null)

                if (signal != null && signal.direction == Direction.BUY) {
                    val entryPrice = currentCandle.close
                    
                    // Determine stop loss and target from strategy metadata or apply standard CNC defaults
                    val stopLoss = (signal.metadata["stopLoss"] as? Double) 
                        ?: (entryPrice * (1.0 - defaultStopLossPercent / 100.0))
                    val target = (signal.metadata["target"] as? Double) 
                        ?: (entryPrice * (1.0 + defaultTargetPercent / 100.0))

                    val riskPerShare = abs(entryPrice - stopLoss)
                    val capitalRiskAmount = currentCapital * (riskPerTradePercent / 100.0)

                    // Calculate safe position size (Strict long cash CNC, no leverage)
                    val maxQtyByCapital = (currentCapital / entryPrice).toInt()
                    if (maxQtyByCapital <= 0) {
                        continue
                    }
                    var qty = if (riskPerShare > 0) (capitalRiskAmount / riskPerShare).toInt() else 1
                    qty = qty.coerceIn(1, maxQtyByCapital)

                    if (qty > 0) {
                        activeTrade = SimulatedActiveTrade(
                            qty = qty,
                            entryPrice = entryPrice,
                            stopLoss = stopLoss,
                            target = target,
                            entryIndex = i,
                            entryTime = currentCandle.timestamp
                        )
                    }
                }
            }
        }

        // Close any remaining active trade at the final candle price
        if (activeTrade != null) {
            val trade = activeTrade
            val finalCandle = candles.last()
            val exitPrice = finalCandle.close
            val pnl = (exitPrice - trade.entryPrice) * trade.qty
            currentCapital += pnl
            equityCurve.add(currentCapital)
            completedTrades.add(
                SimulatedTrade(
                    symbol = strategy.name.take(4).uppercase(),
                    qty = trade.qty,
                    entryPrice = trade.entryPrice,
                    exitPrice = exitPrice,
                    pnl = pnl,
                    isWin = pnl > 0,
                    entryTime = formatter.format(trade.entryTime),
                    exitTime = formatter.format(finalCandle.timestamp),
                    daysHeld = ((candles.size - 1 - trade.entryIndex) / 10).coerceAtLeast(1)
                )
            )
        }

        // Calculate final backtest analytics
        val totalTrades = completedTrades.size
        val wins = completedTrades.count { it.isWin }
        val losses = totalTrades - wins
        val winRate = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100.0 else 0.0
        val netProfit = currentCapital - startingCapital

        val grossProfits = completedTrades.filter { it.isWin }.sumOf { it.pnl }
        val grossLosses = completedTrades.filter { !it.isWin }.sumOf { abs(it.pnl) }
        val profitFactor = if (grossLosses > 0.0) grossProfits / grossLosses else if (grossProfits > 0.0) 99.0 else 1.0

        // Max Drawdown Calculation
        var maxDrawdown = 0.0
        var peak = startingCapital
        for (capital in equityCurve) {
            if (capital > peak) {
                peak = capital
            }
            val drawdown = (peak - capital) / peak * 100.0
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
            }
        }

        return BacktestResult(
            strategyName = strategy.name,
            totalTrades = totalTrades,
            wins = wins,
            losses = losses,
            winRate = winRate,
            netProfit = netProfit,
            profitFactor = profitFactor,
            maxDrawdownPercent = maxDrawdown,
            completedTrades = completedTrades,
            equityCurve = equityCurve
        )
    }

    private data class SimulatedActiveTrade(
        val qty: Int,
        val entryPrice: Double,
        val stopLoss: Double,
        val target: Double,
        val entryIndex: Int,
        val entryTime: Instant
    )
}
