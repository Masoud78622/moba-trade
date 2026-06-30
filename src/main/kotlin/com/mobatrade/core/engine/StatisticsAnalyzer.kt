package com.mobatrade.core.engine

import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

object StatisticsAnalyzer {

    fun calculateStats(customResultsFile: File? = null, customSignalsFile: File? = null): JSONObject {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val shadowDir = if (isWindows) File("c:\\moba trade\\shadow_trades") else File("shadow_trades")
        val resultsFile = customResultsFile ?: File(shadowDir, "version_f_results.csv")
        val signalsFile = customSignalsFile ?: File(shadowDir, "version_f_signals.csv")

        val stats = JSONObject()
        stats.put("signalsFileExists", signalsFile.exists())
        stats.put("resultsFileExists", resultsFile.exists())

        if (!resultsFile.exists()) {
            stats.put("totalTrades", 0)
            stats.put("wins", 0)
            stats.put("losses", 0)
            stats.put("winRatePct", 0.0)
            stats.put("avgWinPct", 0.0)
            stats.put("avgLossPct", 0.0)
            stats.put("profitFactor", 0.0)
            stats.put("expectancyPct", 0.0)
            stats.put("avgRMultiple", 0.0)
            stats.put("avgHoldTimeDays", 0.0)
            stats.put("medianReturnPct", 0.0)
            stats.put("maxConsecutiveLosses", 0)
            return stats
        }

        try {
            val lines = resultsFile.readLines(StandardCharsets.UTF_8)
            if (lines.size <= 1) {
                stats.put("totalTrades", 0)
                stats.put("wins", 0)
                stats.put("losses", 0)
                stats.put("winRatePct", 0.0)
                stats.put("avgWinPct", 0.0)
                stats.put("avgLossPct", 0.0)
                stats.put("profitFactor", 0.0)
                stats.put("expectancyPct", 0.0)
                stats.put("avgRMultiple", 0.0)
                stats.put("avgHoldTimeDays", 0.0)
                stats.put("medianReturnPct", 0.0)
                stats.put("maxConsecutiveLosses", 0)
                return stats
            }

            val headers = lines[0].split(",").map { it.replace("\"", "").trim() }
            val rows = ArrayList<Map<String, String>>()
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val values = line.split(",").map { it.replace("\"", "").trim() }
                if (values.size == headers.size) {
                    rows.add(headers.zip(values).toMap())
                }
            }

            if (rows.isEmpty()) {
                stats.put("totalTrades", 0)
                stats.put("wins", 0)
                stats.put("losses", 0)
                stats.put("winRatePct", 0.0)
                stats.put("avgWinPct", 0.0)
                stats.put("avgLossPct", 0.0)
                stats.put("profitFactor", 0.0)
                stats.put("expectancyPct", 0.0)
                stats.put("avgRMultiple", 0.0)
                stats.put("avgHoldTimeDays", 0.0)
                stats.put("medianReturnPct", 0.0)
                stats.put("maxConsecutiveLosses", 0)
                return stats
            }

            // Chronological sort by exit date to calculate consecutive losses correctly
            val sortedRows = rows.sortedBy { it["ExitDate"] ?: "" }

            var wins = 0
            var losses = 0
            var sumGains = 0.0
            var sumLosses = 0.0
            var sumWinPct = 0.0
            var sumLossPct = 0.0
            var sumRMultiple = 0.0
            var sumHoldTime = 0.0
            var countHoldTime = 0

            val exitsByReason = HashMap<String, Int>()
            val plList = ArrayList<Double>()

            var maxConsecutiveLosses = 0
            var currentConsecutiveLosses = 0

            for (row in sortedRows) {
                val plStr = row["PLPct"]?.replace("%", "") ?: "0.0"
                val pl = plStr.toDoubleOrNull() ?: 0.0
                val rStr = row["RMultiple"] ?: "0.0"
                val r = rStr.toDoubleOrNull() ?: 0.0
                val daysStr = row["DaysToExit"] ?: ""
                val days = daysStr.toDoubleOrNull()
                val reason = row["ExitReason"] ?: "UNKNOWN"

                exitsByReason[reason] = exitsByReason.getOrDefault(reason, 0) + 1
                sumRMultiple += r
                plList.add(pl)

                if (days != null) {
                    sumHoldTime += days
                    countHoldTime++
                }

                if (pl > 0.0) {
                    wins++
                    sumGains += pl
                    sumWinPct += pl
                    currentConsecutiveLosses = 0
                } else {
                    losses++
                    sumLosses += Math.abs(pl)
                    sumLossPct += pl
                    currentConsecutiveLosses++
                    if (currentConsecutiveLosses > maxConsecutiveLosses) {
                        maxConsecutiveLosses = currentConsecutiveLosses
                    }
                }
            }

            val total = rows.size
            val winRate = (wins.toDouble() / total) * 100.0
            val lossRate = (losses.toDouble() / total) * 100.0
            
            val avgWin = if (wins > 0) sumWinPct / wins else 0.0
            val avgLoss = if (losses > 0) sumLossPct / losses else 0.0

            val expectancy = (winRate / 100.0 * avgWin) + (lossRate / 100.0 * avgLoss)
            val profitFactor = if (sumLosses > 0.0) sumGains / sumLosses else if (sumGains > 0.0) 99.9 else 0.0
            val avgR = sumRMultiple / total
            val avgHold = if (countHoldTime > 0) sumHoldTime / countHoldTime else 0.0

            // Median P/L
            plList.sort()
            val medianReturn = if (plList.isEmpty()) {
                0.0
            } else if (plList.size % 2 == 1) {
                plList[plList.size / 2]
            } else {
                (plList[plList.size / 2 - 1] + plList[plList.size / 2]) / 2.0
            }

            stats.put("totalTrades", total)
            stats.put("wins", wins)
            stats.put("losses", losses)
            stats.put("winRatePct", Math.round(winRate * 100.0) / 100.0)
            stats.put("avgWinPct", Math.round(avgWin * 100.0) / 100.0)
            stats.put("avgLossPct", Math.round(avgLoss * 100.0) / 100.0)
            stats.put("profitFactor", Math.round(profitFactor * 100.0) / 100.0)
            stats.put("expectancyPct", Math.round(expectancy * 100.0) / 100.0)
            stats.put("avgRMultiple", Math.round(avgR * 100.0) / 100.0)
            stats.put("avgHoldTimeDays", Math.round(avgHold * 100.0) / 100.0)
            stats.put("medianReturnPct", Math.round(medianReturn * 100.0) / 100.0)
            stats.put("maxConsecutiveLosses", maxConsecutiveLosses)

            // Institutional Execution Reliability & Bug Severity Metrics
            val bugMissesFile = File(shadowDir, "version_f_bug_misses.csv")
            var bugMissesCount = 0
            var sumMissedR = 0.0
            if (bugMissesFile.exists()) {
                val bugLines = bugMissesFile.readLines(StandardCharsets.UTF_8).filter { it.trim().isNotEmpty() }
                if (bugLines.size > 1) {
                    val bHeaders = bugLines[0].split(",").map { it.replace("\"", "").trim() }
                    for (i in 1 until bugLines.size) {
                        val v = bugLines[i].split(",").map { it.replace("\"", "").trim() }
                        if (v.size == bHeaders.size) {
                            val row = bHeaders.zip(v).toMap()
                            bugMissesCount++
                            val rVal = row["RMultiple"]?.toDoubleOrNull() ?: 0.0
                            sumMissedR += rVal
                        }
                    }
                }
            }
            val eligibleSignals = total + bugMissesCount
            val reliability = if (eligibleSignals > 0) (total.toDouble() / eligibleSignals) * 100.0 else 100.0
            val totalAvailableR = sumRMultiple + sumMissedR
            val bugSeverity = if (totalAvailableR > 0.0) (sumMissedR / totalAvailableR) * 100.0 else 0.0
            
            // Deployment Gates Status
            val isStrategyGatePassed = expectancy > 0.0 && profitFactor > 1.5
            val isSoftwareGatePassed = reliability >= 95.0 && bugMissesCount == 0

            stats.put("executedTrades", total)
            stats.put("bugMisses", bugMissesCount)
            stats.put("eligibleSignals", eligibleSignals)
            stats.put("executionReliabilityPct", Math.round(reliability * 100.0) / 100.0)
            stats.put("missedRMultiple", Math.round(sumMissedR * 100.0) / 100.0)
            stats.put("totalAvailableRMultiple", Math.round(totalAvailableR * 100.0) / 100.0)
            stats.put("bugSeverityPct", Math.round(bugSeverity * 100.0) / 100.0)
            
            val gatesJson = JSONObject()
            gatesJson.put("strategyGatePassed", isStrategyGatePassed)
            gatesJson.put("softwareGatePassed", isSoftwareGatePassed)
            gatesJson.put("readyForCapitalAllocation", isStrategyGatePassed && isSoftwareGatePassed)
            stats.put("deploymentGates", gatesJson)

            val reasonJson = JSONObject()
            for ((k, v) in exitsByReason) {
                reasonJson.put(k, v)
            }
            stats.put("exitsReasonBreakdown", reasonJson)

        } catch (e: Exception) {
            stats.put("error", e.message)
        }

        return stats
    }
}
