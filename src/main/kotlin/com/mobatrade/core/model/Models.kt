package com.mobatrade.core.model

import java.time.Instant

enum class Direction {
    BUY, SELL, HOLD
}

enum class MarketRegime {
    TRENDING_BULLISH,
    TRENDING_BEARISH,
    RANGING,
    VOLATILE
}

data class Tick(
    val symbol: String,
    val price: Double,
    val volume: Long,
    val timestamp: Instant
)

data class Candle(
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class Signal(
    val symbol: String,
    val direction: Direction,
    val score: Int,
    val strategyName: String,
    val triggerPrice: Double,
    val timestamp: Instant,
    val metadata: Map<String, Any> = emptyMap()
)

data class Order(
    val symbol: String,
    val quantity: Int,
    val price: Double,
    val direction: Direction,
    val orderType: String, // "MARKET", "LIMIT"
    val stopLoss: Double? = null,
    val target: Double? = null
)

data class Position(
    val symbol: String,
    val entryPrice: Double,
    val quantity: Int,
    val direction: Direction,
    var stopLoss: Double,
    var target: Double,
    val entryTime: Instant
)

data class TradeRecord(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val quantity: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val entryTime: Instant,
    val exitTime: Instant,
    val strategyName: String,
    val pnl: Double = (exitPrice - entryPrice) * quantity,
    val isWin: Boolean = pnl > 0.0
)

