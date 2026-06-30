package com.mobatrade.core.engine

import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.FetchResult
import com.mobatrade.core.halal.ShariahFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId

object VerifyBugMissesRunner {

    private val IST = ZoneId.of("Asia/Kolkata")

    @JvmStatic
    fun main(args: Array<String>) {
        println("======================================================================")
        println("   MOBA TRADE // VERSION F BUG MISSES CANDLE-BY-CANDLE REPLAY ENGINE  ")
        println("======================================================================")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val shadowDir = if (isWindows) File("c:\\moba trade\\shadow_trades") else File("shadow_trades")
        val missesFile = File(shadowDir, "version_f_bug_misses.csv")

        if (!missesFile.exists()) {
            println("❌ version_f_bug_misses.csv not found.")
            return
        }

        val lines = missesFile.readLines(StandardCharsets.UTF_8).filter { it.trim().isNotEmpty() }
        if (lines.size <= 1) {
            println("📋 No bug misses to verify.")
            return
        }

        val headers = lines[0].split(",").map { it.replace("\"", "").trim() }
        val rows = ArrayList<MutableMap<String, String>>()
        for (i in 1 until lines.size) {
            val v = lines[i].split(",").map { it.replace("\"", "").trim() }
            if (v.size == headers.size) {
                rows.add(headers.zip(v).toMap().toMutableMap())
            }
        }

        println("🔑 Authenticating with Angel One...")
        val auth = AngelOneClient.ensureAuthenticated()
        if (!auth) {
            System.err.println("❌ Authentication failed.")
            return
        }
        println("✅ Authenticated!")

        println("📜 Warming up TokenIntegrityGuard Scrip Master...")
        TokenIntegrityGuard.ensureMasterDownloaded()
        var waitCount = 0
        while (!TokenIntegrityGuard.isReady() && waitCount < 30) {
            Thread.sleep(1000)
            waitCount++
        }
        println("✅ TokenIntegrityGuard ready=${TokenIntegrityGuard.isReady()}")

        val updatedRows = ArrayList<List<String>>()

        for (row in rows) {
            val tradeId = row["TradeID"] ?: ""
            val symbol = row["Symbol"] ?: ""
            val dateStr = row["Date"] ?: ""
            val status = row["Status"] ?: ""

            println("\n🔍 Replaying trade simulation for $symbol ($tradeId)...")

            val token = TokenIntegrityGuard.verifyAndGetToken(symbol, null)
            if (token == null) {
                println("⚠️ Could not verify token for $symbol")
                updatedRows.add(headers.map { row[it] ?: "" })
                continue
            }

            val fetchResult = kotlinx.coroutines.runBlocking {
                AngelOneClient.fetchHistoricalCandles(token, symbol, "FIVE_MINUTE", 5)
            }

            val candles = if (fetchResult is FetchResult.Success) fetchResult.data else emptyList()
            if (candles.isEmpty()) {
                println("⚠️ No 5-minute candles fetched for $symbol")
                updatedRows.add(headers.map { row[it] ?: "" })
                continue
            }

            val targetDate = try { LocalDate.parse(dateStr) } catch (e: Exception) { LocalDate.now() }
            val dayCandles = candles.filter { it.timestamp.atZone(IST).toLocalDate() == targetDate }

            if (dayCandles.isEmpty()) {
                println("⚠️ No candles found for date $targetDate")
                updatedRows.add(headers.map { row[it] ?: "" })
                continue
            }

            val entryCandle = dayCandles.first()
            val entryPrice = entryCandle.close
            val atr = entryPrice * 0.02 // Default 2% ATR approximation if historical daily ATR not cached
            val stopPrice = entryPrice - (2.0 * atr)
            val targetPrice = entryPrice + (3.5 * atr)

            println("   ├─ Entry Price: ₹${String.format("%.2f", entryPrice)}")
            println("   ├─ Stop Price:  ₹${String.format("%.2f", stopPrice)} (2.0x ATR)")
            println("   └─ Target Price: ₹${String.format("%.2f", targetPrice)} (3.5x ATR)")

            var targetHit = false
            var stopHit = false
            var exitPrice = dayCandles.last().close
            var exitReason = "EOD_CLOSE"

            for (c in dayCandles) {
                if (c.low <= stopPrice) {
                    stopHit = true
                    exitPrice = stopPrice
                    exitReason = "STOP_HIT"
                    break
                }
                if (c.high >= targetPrice) {
                    targetHit = true
                    exitPrice = targetPrice
                    exitReason = "TARGET_HIT"
                    break
                }
            }

            val plPct = ((exitPrice - entryPrice) / entryPrice) * 100.0
            val rMult = if (atr > 0.0) (exitPrice - entryPrice) / (2.0 * atr) else 0.0

            println("   🎯 RESULT: ExitReason=$exitReason | TargetHit=$targetHit | StopHit=$stopHit | P/L=${String.format("%.2f", plPct)}% | R=${String.format("%.2f", rMult)}")

            row["Status"] = "VERIFIED"
            row["TargetHit"] = targetHit.toString()
            row["StopHit"] = stopHit.toString()
            row["RMultiple"] = String.format("%.2f", rMult)
            row["PLPct"] = String.format("%.2f", plPct)

            updatedRows.add(headers.map { row[it] ?: "" })
        }

        // Rewrite CSV with verified outcomes
        val sb = java.lang.StringBuilder()
        sb.append(headers.joinToString(",") { "\"$it\"" }).append("\n")
        for (r in updatedRows) {
            sb.append(r.joinToString(",") { "\"$it\"" }).append("\n")
        }
        missesFile.writeText(sb.toString(), StandardCharsets.UTF_8)
        println("\n📄 Successfully updated version_f_bug_misses.csv with verified outcomes!")
    }
}
