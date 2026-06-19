package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.MarketRegime
import java.io.File

/**
 * Phase 5 — Walk-Forward Signal Replay Engine
 *
 * Walks a sliding window of candles through the full ConfluenceScorer pipeline
 * under different gate configurations and outputs a side-by-side comparison table.
 *
 * This tool lets you answer: "Would Phase 4's BULLISH threshold = 2 have caught
 * more good trades than the old threshold = 3 on the same historical candles?"
 *
 * Two modes:
 *  - Synthetic: generates realistic 5m candles offline (default)
 *  - File mode: loads a saved JSON candle dump (optional)
 *
 * Run via Gradle:  .\gradlew.bat run --args="replay"
 */
object SignalReplayer {

    // ── Config ────────────────────────────────────────────────────────────────

    /** One gate configuration to test. */
    data class Config(
        val name: String,
        /** Score threshold for entry (Phase 4: 2=bullish, 3=ranging, 4=volatile). */
        val scoreThreshold: Int = 3,
        /** Minimum ADX value required to open any trade. */
        val adxThreshold: Double = 18.0,
        /** Risk-to-reward multiplier used to calculate the target. */
        val rewardToRiskRatio: Double = 2.0
    )

    // ── Trade simulation ──────────────────────────────────────────────────────

    private data class SimTrade(
        val entryPrice: Double,
        val stopLoss: Double,
        val target: Double,
        val riskPerShare: Double,
        var exitPrice: Double = 0.0,
        var isWin: Boolean = false,
        var rMultiple: Double = 0.0
    )

    // ── Result ────────────────────────────────────────────────────────────────

    data class ReplayResult(
        val config: Config,
        val symbol: String,
        val signalsGenerated: Int,
        val tradesTaken: Int,
        val wins: Int,
        val losses: Int,
        val avgRMultiple: Double,
        /** Simulated net P&L assuming 1% of ₹13k capital risked per trade. */
        val netPnL: Double
    ) {
        val winRate: Double
            get() = if (tradesTaken > 0) (wins.toDouble() / tradesTaken) * 100.0 else 0.0
    }

    // ── Walk-forward engine ───────────────────────────────────────────────────

    /**
     * Slides a [windowSize]-candle window over [candles], scores each window
     * with the full ConfluenceScorer, and simulates a trade when the score
     * meets [config.scoreThreshold].
     *
     * Trade lifecycle:
     *  - Entry:  open of candle *after* the signal fires
     *  - Stop:   entry − 1.5 × ATR14
     *  - Target: entry + (1.5 × ATR14) × rewardToRiskRatio
     *  - Resolved over the next 20 candles (SL or target first-touch)
     *  - If neither is hit in 20 candles, exits at last close
     */
    fun replay(
        symbol: String,
        sector: String,
        candles: List<Candle>,
        configs: List<Config>,
        windowSize: Int = 60,
        capitalPerTrade: Double = 13000.0
    ): List<ReplayResult> {
        return configs.map { config ->
            val scorer = ConfluenceScorer(symbol, sector, adxThreshold = config.adxThreshold)
            var signalsGenerated = 0
            val trades = mutableListOf<SimTrade>()

            for (i in windowSize until candles.size) {
                val window = candles.subList(i - windowSize, i)
                val scored = scorer.scoreTrade(window)

                if (scored.totalScore >= config.scoreThreshold &&
                    scored.recommendedDirection == Direction.BUY
                ) {
                    signalsGenerated++

                    // Simulate entry on the NEXT candle's open
                    if (i < candles.size) {
                        val entryCandle = candles[i]
                        val entry = entryCandle.open
                        val atr = if (scored.atr14 > 0) scored.atr14 else entry * 0.015
                        val riskDist = atr * 1.5
                        val sl = entry - riskDist
                        val tp = entry + riskDist * config.rewardToRiskRatio

                        if (sl > 0 && tp > entry) {
                            val trade = SimTrade(entry, sl, tp, riskDist)
                            val futureEnd = minOf(i + 20, candles.size)
                            val future = candles.subList(i, futureEnd)

                            for (fc in future) {
                                if (fc.low <= sl) {
                                    // Stop-loss hit
                                    trade.exitPrice = sl
                                    trade.isWin = false
                                    trade.rMultiple = -1.0
                                    break
                                } else if (fc.high >= tp) {
                                    // Target hit
                                    trade.exitPrice = tp
                                    trade.isWin = true
                                    trade.rMultiple = config.rewardToRiskRatio
                                    break
                                }
                            }

                            // Neither hit — exit at last close of the 20-candle window
                            if (trade.exitPrice == 0.0 && future.isNotEmpty()) {
                                trade.exitPrice = future.last().close
                                val pnl = trade.exitPrice - entry
                                trade.rMultiple = pnl / riskDist
                                trade.isWin = pnl > 0
                            }
                            trades.add(trade)
                        }
                    }
                }
            }

            val wins = trades.count { it.isWin }
            val avgR = if (trades.isNotEmpty()) trades.map { it.rMultiple }.average() else 0.0
            // 1% risk model: risk ₹130 per trade (1% of ₹13k)
            val riskPerTrade = capitalPerTrade * 0.01
            val netPnL = trades.sumOf { it.rMultiple * riskPerTrade }

            ReplayResult(
                config = config,
                symbol = symbol,
                signalsGenerated = signalsGenerated,
                tradesTaken = trades.size,
                wins = wins,
                losses = trades.size - wins,
                avgRMultiple = avgR,
                netPnL = netPnL
            )
        }
    }

    // ── Output ────────────────────────────────────────────────────────────────

    fun printTable(results: List<ReplayResult>) {
        val sep = "─".repeat(104)
        println("\n$sep")
        println("  SIGNAL REPLAY ENGINE — GATE CONFIG COMPARISON (Walk-forward, 2,000 candles per stock)")
        println(sep)
        System.out.printf(
            "│ %-18s │ %-8s │ %-8s │ %-8s │ %-8s │ %-7s │ %-7s │ %-13s │\n",
            "CONFIG", "SYMBOL", "SIGNALS", "TRADES", "WIN %", "AVG R", "LOSSES", "NET P&L"
        )
        println(sep)
        for (r in results) {
            val winColor = if (r.winRate >= 50) "✅" else "⚠️ "
            System.out.printf(
                "│ %-18s │ %-8s │ %-8d │ %-8d │ %s %-5.1f%% │ %-7.2f │ %-7d │ ₹%-12.2f │\n",
                r.config.name, r.symbol, r.signalsGenerated, r.tradesTaken,
                winColor, r.winRate, r.avgRMultiple, r.losses, r.netPnL
            )
        }
        println(sep)
    }

    // ── HTML report ───────────────────────────────────────────────────────────

    private fun generateHtml(results: List<ReplayResult>): String {
        val rows = results.joinToString("") { r ->
            val winColor = if (r.winRate >= 50) "#10B981" else "#EF4444"
            val pnlColor = if (r.netPnL >= 0) "#10B981" else "#EF4444"
            val rColor = if (r.avgRMultiple >= 1.0) "#10B981" else "#EF4444"
            """
            <tr>
              <td class="mono bold">${r.config.name}</td>
              <td class="mono">${r.symbol}</td>
              <td class="mono center">${r.signalsGenerated}</td>
              <td class="mono center">${r.tradesTaken}</td>
              <td class="mono center" style="color:$winColor">${String.format("%.1f", r.winRate)}%</td>
              <td class="mono center" style="color:$rColor">${String.format("%.2f", r.avgRMultiple)}R</td>
              <td class="mono center">${r.losses}</td>
              <td class="mono right" style="color:$pnlColor">₹${String.format("%.2f", r.netPnL)}</td>
            </tr>""".trimIndent()
        }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Moba Trade // Signal Replay Report</title>
  <style>
    body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#0A0A0C;color:#fff;margin:40px}
    h1{font-family:monospace;font-size:18px;border-bottom:1px solid #27272A;padding-bottom:10px;margin-bottom:20px}
    .badge{background:#fff;color:#000;padding:4px 10px;font-family:monospace;font-size:11px;font-weight:bold;border-radius:2px;display:inline-block;margin-bottom:20px}
    table{width:100%;border-collapse:collapse;background:#121215;border:1px solid #27272A;font-size:13px}
    th{font-family:monospace;background:#1c1c21;color:#A1A1AA;padding:12px;text-align:left;border-bottom:1px solid #27272A}
    td{padding:10px 12px;border-bottom:1px solid #1c1c21;color:#fff}
    tr:hover{background:#1a1a20}
    .mono{font-family:monospace}.bold{font-weight:bold}.center{text-align:center}.right{text-align:right}
    .footer{font-family:monospace;font-size:10px;color:#71717A;margin-top:20px}
  </style>
</head>
<body>
  <div class="badge">PHASE 5 // SIGNAL REPLAY ENGINE // WALK-FORWARD BACKTEST</div>
  <h1>MOBA TRADE // GATE CONFIG COMPARISON</h1>
  <table>
    <thead>
      <tr>
        <th>CONFIG</th><th>SYMBOL</th><th>SIGNALS</th><th>TRADES</th>
        <th style="text-align:center">WIN %</th>
        <th style="text-align:center">AVG R</th>
        <th style="text-align:center">LOSSES</th>
        <th style="text-align:right">NET P&amp;L</th>
      </tr>
    </thead>
    <tbody>$rows</tbody>
  </table>
  <p class="footer">Walk-forward on 2,000 synthetic 5m candles per stock. Risk model: 1% of ₹13,000 = ₹130 per trade.</p>
</body>
</html>""".trimIndent()
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Configs compared:
     *  A — Pre-Phase1 baseline (ADX>25, score≥3) — the old strict config
     *  B — Phase2 (ADX>18, score≥3)              — current production standard
     *  C — Phase4 BULLISH (ADX>18, score≥2)      — relaxed bull-market threshold
     *  D — Phase4 VOLATILE (ADX>18, score≥4)     — tight choppy-market threshold
     */
    @JvmStatic
    fun main(args: Array<String>) {
        println("=" .repeat(80))
        println("  MOBA TRADE // PHASE 5 — SIGNAL REPLAY ENGINE")
        println("=" .repeat(80))

        val configs = listOf(
            Config("A: Baseline",     scoreThreshold = 3, adxThreshold = 25.0),
            Config("B: Phase2 Std",   scoreThreshold = 3, adxThreshold = 18.0),
            Config("C: Phase4 Bull",  scoreThreshold = 2, adxThreshold = 18.0),
            Config("D: Phase4 Vol",   scoreThreshold = 4, adxThreshold = 18.0)
        )

        // Stocks: (symbol, sector, seed price)
        val stocks = listOf(
            Triple("TCS",      "IT",     3045.0),
            Triple("INFY",     "IT",     1520.0),
            Triple("RELIANCE", "ENERGY", 2450.0),
            Triple("HCLTECH",  "IT",     1300.0),
            Triple("IGL",      "ENERGY",  165.0)
        )

        val allResults = mutableListOf<ReplayResult>()

        for ((symbol, sector, price) in stocks) {
            print("  Generating candles for $symbol ($sector, ₹$price)... ")
            // Mix of regimes: bullish (1200 candles) + ranging (800 candles) for realistic coverage
            val bull    = MarketDataService.generateSyntheticData(MarketRegime.TRENDING_BULLISH, 1200, price)
            val ranging = MarketDataService.generateSyntheticData(MarketRegime.RANGING, 800, bull.last().close)
            val candles = bull + ranging
            println("${candles.size} candles ready.")

            val results = replay(symbol, sector, candles, configs)
            allResults.addAll(results)
        }

        printTable(allResults)

        // Save HTML report
        val reportsDir = File("c:\\moba trade\\backtest_reports")
        reportsDir.mkdirs()
        val reportFile = File(reportsDir, "signal_replay_report.html")
        reportFile.writeText(generateHtml(allResults))
        println("\n  📊 HTML report saved → ${reportFile.absolutePath}")
        println("=" .repeat(80))
    }
}
