package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.MarketRegime
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Random

object MarketDataService {

    private val random = Random(42) // Seeded for deterministic testing

    /**
     * Generates extremely lifelike mock candlestick historical data using a random walk
     * with drift parameters adapted for different market regimes (trending, ranging, volatile).
     */
    fun generateSyntheticData(
        regime: MarketRegime = MarketRegime.TRENDING_BULLISH,
        candleCount: Int = 1000,
        startPrice: Double = 3000.0
    ): List<Candle> {
        val candles = ArrayList<Candle>()
        var currentPrice = startPrice
        val now = Instant.now()

        // Drift and volatility multipliers depending on regime
        val (drift, volatility) = when (regime) {
            MarketRegime.TRENDING_BULLISH -> 0.0004 to 0.004
            MarketRegime.TRENDING_BEARISH -> -0.0006 to 0.005
            MarketRegime.RANGING -> 0.0 to 0.002
            MarketRegime.VOLATILE -> 0.0 to 0.012
        }

        for (i in 0 until candleCount) {
            // Standard geometric Brownian motion style random walk
            var changePercent = drift + (random.nextGaussian() * volatility)
            
            // Ranging regime has mean-reversion force to keep it in a channel
            if (regime == MarketRegime.RANGING) {
                val deviation = (currentPrice - startPrice) / startPrice
                changePercent -= deviation * 0.05 // Pull price back toward starting center
            }

            val open = currentPrice
            val close = currentPrice * (1.0 + changePercent)
            
            val upperVolatility = random.nextDouble() * (currentPrice * volatility * 0.5)
            val lowerVolatility = random.nextDouble() * (currentPrice * volatility * 0.5)
            val high = maxOf(open, close) + upperVolatility
            val low = minOf(open, close) - lowerVolatility
            
            val volume = (10_000 + random.nextInt(50_000)).toLong()
            
            val candle = Candle(
                timestamp = now.minus((candleCount - i).toLong(), ChronoUnit.HOURS),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
            candles.add(candle)
            currentPrice = close
        }

        return candles
    }

    /**
     * Parses historical CSV files to ingest real market data.
     * Expects standard format: Date, Open, High, Low, Close, Volume
     */
    fun loadCsvData(filePath: String): List<Candle> {
        val file = File(filePath)
        if (!file.exists()) {
            System.err.println("CSV Data file not found at: $filePath. Falling back to synthetic bullish data.")
            return generateSyntheticData(MarketRegime.TRENDING_BULLISH, 500)
        }

        val candles = ArrayList<Candle>()
        try {
            val lines = file.readLines()
            if (lines.size <= 1) return emptyList()

            // Detect column header mappings
            val headers = lines[0].split(",").map { it.trim().uppercase() }
            val dateIdx = headers.indexOfFirst { it.contains("DATE") || it.contains("TIME") }
            val openIdx = headers.indexOfFirst { it == "OPEN" }
            val highIdx = headers.indexOfFirst { it == "HIGH" }
            val lowIdx = headers.indexOfFirst { it == "LOW" }
            val closeIdx = headers.indexOfFirst { it == "CLOSE" }
            val volIdx = headers.indexOfFirst { it.contains("VOL") }

            if (openIdx < 0 || highIdx < 0 || lowIdx < 0 || closeIdx < 0 || dateIdx < 0) {
                System.err.println("CSV Header parse failed. Missing required columns.")
                return emptyList()
            }

            for (i in 1 until lines.size) {
                val cols = lines[i].split(",").map { it.trim() }
                if (cols.size < 5) continue

                val open = cols[openIdx].toDoubleOrNull() ?: continue
                val high = cols[highIdx].toDoubleOrNull() ?: continue
                val low = cols[lowIdx].toDoubleOrNull() ?: continue
                val close = cols[closeIdx].toDoubleOrNull() ?: continue
                val volume = if (volIdx >= 0 && volIdx < cols.size) {
                    cols[volIdx].replace(".0", "").toLongOrNull() ?: 1000L
                } else {
                    1000L
                }

                val timestamp = try {
                    Instant.parse(cols[dateIdx])
                } catch (e: Exception) {
                    // Fallback incremental timestamp
                    Instant.now().minus((lines.size - i).toLong(), ChronoUnit.DAYS)
                }

                candles.add(Candle(timestamp, open, high, low, close, volume))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return candles
    }
}
