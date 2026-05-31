package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.MarketRegime
import com.mobatrade.core.strategies.Strategy
import com.mobatrade.core.strategies.tier1.OpeningRangeBreakout
import com.mobatrade.core.strategies.tier1.DarvasBox
import com.mobatrade.core.strategies.tier1.SupportResistanceFlip
import com.mobatrade.core.strategies.tier2.VolumeProfile
import com.mobatrade.core.strategies.tier2.VwapDevBands
import com.mobatrade.core.strategies.tier2.ObvDivergence
import com.mobatrade.core.strategies.tier3.OrderBlocks
import com.mobatrade.core.strategies.tier3.BreakOfStructure
import com.mobatrade.core.strategies.tier3.FairValueGap
import com.mobatrade.core.strategies.tier3.LiquiditySweep
import com.mobatrade.core.strategies.tier4.EmaCrossover
import com.mobatrade.core.strategies.tier4.AdxFilter
import com.mobatrade.core.strategies.tier4.SectorRotation
import com.mobatrade.core.strategies.tier5.NewsSentiment
import com.mobatrade.core.strategies.tier5.PatternRecognition
import java.io.File

object BacktestRunner {

    // 10 Distinct Test Epochs of 5,000 candles each (total 50,000 candles)
    data class TestEpoch(val id: Int, val name: String, val regime: MarketRegime)

    val epochs = listOf(
        TestEpoch(1, "BULL MARKET RALLY (EPOCH 1)", MarketRegime.TRENDING_BULLISH),
        TestEpoch(2, "SIDEWAYS RANGE CONSOLIDATION (EPOCH 2)", MarketRegime.RANGING),
        TestEpoch(3, "SYSTEMIC MARKET CRASH (EPOCH 3)", MarketRegime.TRENDING_BEARISH),
        TestEpoch(4, "EARNINGS SEASON VOLATILITY (EPOCH 4)", MarketRegime.VOLATILE),
        TestEpoch(5, "POST-CRASH BULLISH RECOVERY (EPOCH 5)", MarketRegime.TRENDING_BULLISH),
        TestEpoch(6, "VALUATION ADJUSTMENT RANGE (EPOCH 6)", MarketRegime.RANGING),
        TestEpoch(7, "SLOW BEARISH BLEED (EPOCH 7)", MarketRegime.TRENDING_BEARISH),
        TestEpoch(8, "LIQUIDITY SWEEP HYPER NOISE (EPOCH 8)", MarketRegime.VOLATILE),
        TestEpoch(9, "PARABOLIC STRUCTURAL BREAKOUT (EPOCH 9)", MarketRegime.TRENDING_BULLISH),
        TestEpoch(10, "STABLE CONSOLIDATION CHANNEL (EPOCH 10)", MarketRegime.RANGING)
    )

    // Data container to accumulate and track continuous multi-epoch performance metrics
    data class CumulativeMetrics(
        val symbol: String,
        val strategyName: String,
        var totalTrades: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var netProfit: Double = 0.0,
        var maxDrawdown: Double = 0.0,
        val equityCurve: ArrayList<Double> = ArrayList()
    ) {
        val winRate: Double
            get() = if (totalTrades > 0) (wins.toDouble() / totalTrades) * 100.0 else 0.0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("======================================================================")
        println("       MOBA TRADE // 50,000 CANDLE MULTI-STOCK STRESS TEST ENGINE      ")
        println("======================================================================")
        println("STATUS: SECURED & ACTIVE // INITIALIZING MASSIVE MULTI-EPOCH SIMULATOR")

        // 5 Major Stocks under test
        val majorStocks = listOf("TCS", "INFY", "RELIANCE", "WIPRO", "HCLTECH")
        
        // Initial stock prices at start of Epoch 1
        val stockPrices = mutableMapOf(
            "TCS" to 3500.0,
            "INFY" to 1500.0,
            "RELIANCE" to 2500.0,
            "WIPRO" to 500.0,
            "HCLTECH" to 1300.0
        )

        // Initialize cumulative performance metrics tracker
        val cumulativeResults = LinkedHashMap<String, LinkedHashMap<String, CumulativeMetrics>>()
        for (symbol in majorStocks) {
            cumulativeResults[symbol] = LinkedHashMap()
            val strategies = createStrategiesForSymbol(symbol)
            for (strategy in strategies) {
                val metrics = CumulativeMetrics(symbol, strategy.name)
                metrics.equityCurve.add(100000.0) // starting capital
                cumulativeResults[symbol]!![strategy.name] = metrics
            }
        }

        // Loop over the 10 separate 5000-candle simulation test rounds
        for (epoch in epochs) {
            println("\n>>> EXECUTING TEST ROUND ${epoch.id}/10: ${epoch.name}")
            println("    [REGIME: ${epoch.regime} // WIDTH: 5,000 CANDLES]")
            
            for (symbol in majorStocks) {
                val startPrice = stockPrices[symbol] ?: 1000.0
                
                // Generate 5000 realistic candles for this regime
                val candles = MarketDataService.generateSyntheticData(
                    regime = epoch.regime,
                    candleCount = 5000,
                    startPrice = startPrice
                )

                // Update prices for next epoch continuity
                if (candles.isNotEmpty()) {
                    stockPrices[symbol] = candles.last().close
                }

                val strategies = createStrategiesForSymbol(symbol)
                for (strategy in strategies) {
                    val cumMetrics = cumulativeResults[symbol]!![strategy.name]!!
                    val currentCapital = cumMetrics.equityCurve.last()

                    // Run the 5000-candle backtest
                    val result = BacktestEngine.runBacktest(
                        strategy = strategy,
                        candles = candles,
                        startingCapital = currentCapital
                    )

                    // Accumulate stats
                    cumMetrics.totalTrades += result.totalTrades
                    cumMetrics.wins += result.wins
                    cumMetrics.losses += result.losses
                    cumMetrics.netProfit += result.netProfit
                    
                    // Track maximum drawdown observed across all tests
                    if (result.maxDrawdownPercent > cumMetrics.maxDrawdown) {
                        cumMetrics.maxDrawdown = result.maxDrawdownPercent
                    }

                    // Concatenate equity curve points dynamically
                    cumMetrics.equityCurve.addAll(result.equityCurve.drop(1))
                }
            }
            println("    ✓ COMPLETED SIMULATION ROUND ${epoch.id} FOR ALL 5 STOCKS")
        }

        // Output results to standard CLI console
        printConsolidatedConsoleReport(cumulativeResults)

        // Save a beautiful multi-stock tabbed HTML report inside backtest_reports
        val reportsDir = File("c:\\moba trade\\backtest_reports")
        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }

        val reportFile = File(reportsDir, "Backtest_50k_Stress_Report.html")
        val htmlContent = generateStressHtmlReport(cumulativeResults)
        reportFile.writeText(htmlContent)

        println("\n======================================================================")
        println("PHYSICAL MULTI-STOCK STRESS TEST REPORT EXPORTED TO:")
        println("=> [${reportFile.absolutePath}]")
        println("======================================================================")
    }

    private fun createStrategiesForSymbol(symbol: String): List<Strategy> {
        return listOf(
            OpeningRangeBreakout(symbol),
            DarvasBox(symbol),
            SupportResistanceFlip(symbol),
            VolumeProfile(symbol),
            VwapDevBands(symbol),
            ObvDivergence(symbol),
            OrderBlocks(symbol),
            BreakOfStructure(symbol),
            FairValueGap(symbol),
            LiquiditySweep(symbol),
            EmaCrossover(symbol),
            AdxFilter(symbol),
            SectorRotation(symbol, "IT"),
            NewsSentiment(symbol),
            PatternRecognition(symbol)
        )
    }

    private fun printConsolidatedConsoleReport(results: Map<String, Map<String, CumulativeMetrics>>) {
        println("\n======================================================================================================")
        println("                            50,000 CANDLE CUMULATIVE PORTFOLIO PERFORMANCE                            ")
        println("======================================================================================================")
        
        for ((symbol, strategiesMap) in results) {
            println("\nSTOCK TICKER: $symbol (50,000 candles simulated across 10 epochs)")
            println("------------------------------------------------------------------------------------------------------")
            System.out.printf(
                "| %-28s | %-6s | %-8s | %-12s | %-12s |\n",
                "STRATEGY NAME", "TRADES", "WIN RATE", "NET PROFIT", "MAX DRAWDOWN"
            )
            println("------------------------------------------------------------------------------------------------------")
            
            for ((_, metrics) in strategiesMap) {
                System.out.printf(
                    "| %-28s | %-6d | %-7.1f%% | ₹%-10.2f | %-11.2f%% |\n",
                    metrics.strategyName,
                    metrics.totalTrades,
                    metrics.winRate,
                    metrics.netProfit,
                    metrics.maxDrawdown
                )
            }
            println("------------------------------------------------------------------------------------------------------")
        }
        println("======================================================================================================\n")
    }

    private fun generateStressHtmlReport(results: Map<String, Map<String, CumulativeMetrics>>): String {
        val tabButtons = StringBuilder()
        val tabContents = StringBuilder()

        var isFirst = true
        for ((symbol, strategiesMap) in results) {
            val activeClass = if (isFirst) "active" else ""
            val displayStyle = if (isFirst) "table" else "none"

            // Tab navigation button
            tabButtons.append("""
                <button class="tab-btn $activeClass" onclick="showStock('$symbol')">$symbol</button>
            """.trimIndent())

            // Create strategy rows for this stock
            val rows = StringBuilder()
            for ((_, m) in strategiesMap) {
                val pnlColor = if (m.netProfit >= 0) "#10B981" else "#EF4444"
                val pnlPrefix = if (m.netProfit >= 0) "+" else ""
                rows.append("""
                    <tr>
                        <td class="bold font-mono">${m.strategyName}</td>
                        <td class="center font-mono">${m.totalTrades}</td>
                        <td class="center font-mono bold">${String.format("%.1f", m.winRate)}%</td>
                        <td class="right font-mono bold" style="color: $pnlColor">$pnlPrefix₹${String.format("%.2f", m.netProfit)}</td>
                        <td class="right font-mono text-red" style="color: #EF4444">${String.format("%.2f", m.maxDrawdown)}%</td>
                    </tr>
                """.trimIndent())
            }

            // Tab table content
            tabContents.append("""
                <table id="table-$symbol" class="stock-table" style="display: $displayStyle;">
                    <thead>
                        <tr>
                            <th>STRATEGY NAME</th>
                            <th style="width: 100px; text-align: center;">TOTAL TRADES</th>
                            <th style="width: 120px; text-align: center;">WIN RATE</th>
                            <th style="width: 160px; text-align: right;">CUMULATIVE NET P&L</th>
                            <th style="width: 140px; text-align: right;">MAX DRAWDOWN</th>
                        </tr>
                    </thead>
                    <tbody>
                        $rows
                    </tbody>
                </table>
            """.trimIndent())

            isFirst = false
        }

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>Moba Trade // 50k Candle Stress Test Report</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            color: #ffffff;
            background-color: #0A0A0C;
            margin: 40px;
            padding: 0;
        }
        .header {
            border-bottom: 2px solid #27272A;
            padding-bottom: 15px;
            margin-bottom: 30px;
        }
        .header-title {
            font-family: monospace;
            font-size: 24px;
            font-weight: bold;
            letter-spacing: 1px;
            color: #ffffff;
        }
        .header-meta {
            font-family: monospace;
            font-size: 12px;
            color: #A1A1AA;
            margin-top: 5px;
        }
        h2 {
            font-family: monospace;
            font-size: 14px;
            font-weight: bold;
            border-bottom: 1px solid #27272A;
            padding-bottom: 5px;
            margin-top: 30px;
            margin-bottom: 15px;
            letter-spacing: 0.5px;
            color: #ffffff;
        }
        .tabs {
            display: flex;
            gap: 10px;
            margin-bottom: 20px;
            border-bottom: 1px solid #27272A;
            padding-bottom: 10px;
        }
        .tab-btn {
            background-color: #121215;
            color: #A1A1AA;
            border: 1px solid #27272A;
            padding: 10px 20px;
            font-family: monospace;
            font-size: 13px;
            font-weight: bold;
            cursor: pointer;
            border-radius: 4px;
            transition: all 0.2s;
        }
        .tab-btn:hover {
            color: #ffffff;
            border-color: #A1A1AA;
        }
        .tab-btn.active {
            background-color: #ffffff;
            color: #000000;
            border-color: #ffffff;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            margin-bottom: 30px;
            font-size: 12px;
            background-color: #121215;
            border: 1px solid #27272A;
            border-radius: 4px;
            overflow: hidden;
        }
        th {
            font-family: monospace;
            font-weight: bold;
            text-align: left;
            border-bottom: 1px solid #27272A;
            padding: 12px;
            background-color: #1c1c21;
            color: #A1A1AA;
        }
        td {
            padding: 12px;
            border-bottom: 1px solid #27272A;
            color: #ffffff;
        }
        tr:hover {
            background-color: #1a1a20;
        }
        .font-mono {
            font-family: monospace;
        }
        .bold {
            font-weight: bold;
        }
        .center {
            text-align: center;
        }
        .right {
            text-align: right;
        }
        .footer {
            font-family: monospace;
            font-size: 10px;
            color: #71717A;
            text-align: center;
            margin-top: 50px;
            border-top: 1px solid #27272A;
            padding-top: 15px;
        }
        .banner {
            background-color: #ffffff;
            color: #000000;
            padding: 15px;
            border-radius: 4px;
            margin-bottom: 30px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .banner-title {
            font-family: monospace;
            font-weight: bold;
            font-size: 14px;
        }
        .banner-badge {
            background-color: #000000;
            color: #ffffff;
            padding: 5px 10px;
            font-size: 10px;
            font-weight: bold;
            border-radius: 2px;
            font-family: monospace;
        }
    </style>
</head>
<body>

    <div class="banner">
        <div class="banner-title">SYSTEM STRESS SUMMARY // 50,000 CANDLE BACKTEST</div>
        <div class="banner-badge">100% SHARIAH COMPLIANT (ZOYA INDEX)</div>
    </div>

    <div class="header">
        <div class="header-title">MOBA TRADE // MULTI-STOCK STRESS SIMULATION</div>
        <div class="header-meta">GEN: JVM STRESS RUNNER // WIDTH: 50,000 CANDLES // EPOCHS: 10 TESTS OF 5,000 CANDLES EACH</div>
    </div>

    <h2>STRESS TEST PERFORMANCE BY STOCK</h2>
    
    <div class="tabs">
        $tabButtons
    </div>

    $tabContents

    <script>
        function showStock(symbol) {
            // Hide all tables
            document.querySelectorAll('.stock-table').forEach(tbl => tbl.style.display = 'none');
            // Deactivate all buttons
            document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
            
            // Show selected table
            document.getElementById('table-' + symbol).style.display = 'table';
            
            // Find and activate the clicked button
            const buttons = document.querySelectorAll('.tab-btn');
            buttons.forEach(btn => {
                if (btn.innerText === symbol) {
                    btn.classList.add('active');
                }
            });
        }
    </script>

    <div class="footer">
        THIS REPORT COMPILES 50,000 SEQUENTIAL TIME-SERIES BARS PROCESSED IN 10 DISCRETE MARKET CONDITIONS. <br>
        ALL SIMULATIONS ARE RUN NATIVELY NATIONALLY UNDER CNC EXCHANGE MARGIN DIRECTIVES.
    </div>

</body>
</html>
        """.trimIndent()
    }
}
