package com.mobatrade.core.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.OrderResult
import com.mobatrade.core.model.Position
import com.mobatrade.core.model.Direction
import java.time.Instant

object PaperTradingStorage {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val POSITIONS_FILE = if (isWindows) File("c:\\moba trade\\paper_positions.json") else File("paper_positions.json")
    private val CAPITAL_FILE = if (isWindows) File("c:\\moba trade\\paper_capital.json") else File("paper_capital.json")

    private const val DEFAULT_CAPITAL = 100_000.0

    @Synchronized
    fun loadCapital(): Double {
        if (!CAPITAL_FILE.exists()) {
            val initialCapital = EnvLoader.get("PAPER_TRADING_CAPITAL")?.toDoubleOrNull() ?: DEFAULT_CAPITAL
            CAPITAL_FILE.writeText(initialCapital.toString(), StandardCharsets.UTF_8)
            return initialCapital
        }
        return CAPITAL_FILE.readText(StandardCharsets.UTF_8).trim().toDoubleOrNull() ?: DEFAULT_CAPITAL
    }

    @Synchronized
    fun saveCapital(capital: Double) {
        CAPITAL_FILE.writeText(capital.toString(), StandardCharsets.UTF_8)
    }

    @Synchronized
    fun loadPositions(): List<JSONObject> {
        if (!POSITIONS_FILE.exists() || POSITIONS_FILE.length() <= 2) {
            return emptyList()
        }
        val results = ArrayList<JSONObject>()
        try {
            val array = JSONArray(POSITIONS_FILE.readText(StandardCharsets.UTF_8))
            for (i in 0 until array.length()) {
                results.add(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            System.err.println("PaperTradingStorage: Failed to load positions: ${e.message}")
        }
        return results
    }

    @Synchronized
    fun savePositions(positions: List<Position>) {
        val array = JSONArray()
        for (pos in positions) {
            val obj = JSONObject()
            obj.put("symbol", pos.symbol)
            obj.put("qty", pos.quantity)
            obj.put("netqty", pos.quantity)
            obj.put("buyavgprice", pos.entryPrice)
            obj.put("averageprice", pos.entryPrice)
            obj.put("isSwing", pos.isSwing)
            obj.put("entryTime", pos.entryTime.toString())
            obj.put("stopLoss", pos.stopLoss)
            obj.put("target", pos.target)
            obj.put("firstPartialDone", pos.firstPartialDone)
            obj.put("secondPartialDone", pos.secondPartialDone)
            obj.put("atr14", pos.atr14)
            obj.put("highestClose", pos.highestClose)
            obj.put("initialRiskPerShare", pos.initialRiskPerShare)
            obj.put("realised", 0.0)
            obj.put("unrealised", 0.0)
            
            // Fetch token for mapping
            val info = TokenIntegrityGuard.getTokenInfoForSymbol(pos.symbol)
            obj.put("symboltoken", info?.first ?: "")
            obj.put("token", info?.first ?: "")
            
            array.put(obj)
        }
        POSITIONS_FILE.writeText(array.toString(), StandardCharsets.UTF_8)
    }

    @Synchronized
    fun executePaperBuy(order: Order, currentCapital: Double): OrderResult {
        val cost = order.quantity * order.price
        if (cost > currentCapital) {
            return OrderResult.Failure("Insufficient paper trading capital")
        }
        val newCapital = currentCapital - cost
        saveCapital(newCapital)
        println("📝 [PAPER TRADE] BUY execution: Placed order for ${order.quantity} shares of ${order.symbol} @ ₹${order.price}. Cost: ₹$cost. Remaining Capital: ₹$newCapital")
        return OrderResult.Success("PAPER_BUY_${System.currentTimeMillis()}")
    }

    @Synchronized
    fun executePaperSell(symbol: String, qty: Int): OrderResult {
        // Load positions
        val positions = loadPositions()
        val posObj = positions.find { it.optString("symbol").uppercase() == symbol.uppercase() }
        if (posObj == null) {
            return OrderResult.Failure("Position not found for $symbol")
        }
        
        // Fetch current price
        val token = posObj.optString("symboltoken", "")
        val currentPrice = AngelOneClient.fetchRealLtp(symbol, token)
        val exitPrice = if (currentPrice > 0.0) currentPrice else posObj.optDouble("buyavgprice")
        
        val revenue = qty * exitPrice
        val currentCapital = loadCapital()
        val newCapital = currentCapital + revenue
        saveCapital(newCapital)
        
        println("📝 [PAPER TRADE] SELL execution: Liquidated $qty shares of $symbol @ ₹$exitPrice. Revenue: ₹$revenue. New Capital: ₹$newCapital")
        
        return OrderResult.Success("PAPER_SELL_${System.currentTimeMillis()}")
    }
}
