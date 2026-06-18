package com.mobatrade.core.engine

object TradingConstants {
    // History required to properly warm up indicators like EMA50 and Wilder's Smoothed ADX
    const val CANDLE_HISTORY_DAYS_INTRADAY_SCORING = 20
    
    // History required for breakout and ATR confirmation
    const val CANDLE_HISTORY_DAYS_BREAKOUT_CONFIRM = 30
    
    // History required for daily timeframes (approx 200 trading days)
    const val CANDLE_HISTORY_DAYS_DAILY_AUDIT = 300
}
