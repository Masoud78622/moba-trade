package com.mobatrade.core.strategies

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Signal
import com.mobatrade.core.model.Tick

interface Strategy {
    val name: String
    
    /**
     * Evaluates the latest market data to determine if a signal should be generated.
     * @param candles Historical candles for technical analysis (e.g., 5m, 15m, or Daily depending on timeframe).
     * @param currentTick The latest real-time tick (optional, for execution triggers).
     * @return Signal containing trade direction, score contribution, and trigger price, or null if no signal.
     */
    fun evaluate(candles: List<Candle>, currentTick: Tick? = null): Signal?
}
