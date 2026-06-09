package com.mobatrade.core.halal

import org.json.JSONObject
import org.json.JSONArray
import java.io.File

object ShariahFilter {
    // Set of compliant tokens (or symbols) for O(1) lookups
    private val compliantSymbols = HashSet<String>()
    private val compliantTokens = HashSet<String>()
    private const val DEFAULT_CACHE_PATH = "c:\\moba trade\\halal_stocks.json"

    /**
     * Load the Halal universe from a JSON file.
     * The file structure is expected to be a JSON array of objects:
     * [
     *   {"symbol": "TCS", "token": "11536", "sector": "IT"},
     *   {"symbol": "INFY", "token": "1594", "sector": "IT"}
     * ]
     */
    fun loadUniverse(jsonFilePath: String = DEFAULT_CACHE_PATH): Boolean {
        return try {
            val file = File(jsonFilePath)
            if (!file.exists()) {
                return false
            }
            val content = file.readText()
            val array = JSONArray(content)
            
            synchronized(this) {
                compliantSymbols.clear()
                compliantTokens.clear()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val symbol = obj.optString("symbol")
                    val token = obj.optString("token")
                    if (symbol.isNotEmpty()) compliantSymbols.add(symbol.uppercase())
                    if (token.isNotEmpty()) compliantTokens.add(token)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Integrates with Zoya Sync Service to run a one-time synchronization if the
     * local cache is not available, then loads the synchronized stock database.
     */
    @Synchronized
    fun initializeWithSync(apiKey: String, cachePath: String = DEFAULT_CACHE_PATH): Boolean {
        val file = File(cachePath)
        if (file.exists()) {
            println("Loading Shariah universe from existing local cache: $cachePath")
            val success = loadUniverse(cachePath)
            if (success && size() > 0) {
                return true
            }
        }

        // Cache missing or corrupted, trigger one-time paginated Zoya GraphQL Sync
        println("Local cache missing or empty. Pulling fresh Shariah-compliant universe from Zoya Sandbox API...")
        val stocks = ZoyaSyncService.syncCompliantStocks(apiKey)
        if (stocks.isNotEmpty()) {
            val saved = ZoyaSyncService.saveCompliantUniverseToCache(stocks, cachePath)
            if (saved) {
                println("Shariah universe cached successfully with ${stocks.size} stocks.")
                return loadUniverse(cachePath)
            }
        }
        
        System.err.println("Zoya Sync failed or returned 0 stocks. Initializing with fallback mock universe.")
        initializeManual(
            symbols = listOf("TCS", "INFY", "WIPRO"),
            tokens = listOf("11536", "1594", "3787")
        )
        return false
    }

    /**
     * Manually initialize with a list of symbols/tokens.
     */
    fun initializeManual(symbols: List<String>, tokens: List<String>) {
        synchronized(this) {
            compliantSymbols.clear()
            compliantTokens.clear()
            compliantSymbols.addAll(symbols.map { it.uppercase() })
            compliantTokens.addAll(tokens)
        }
    }

    /**
     * Checks if a symbol is Shariah compliant.
     * O(1) performance.
     */
    fun isCompliantSymbol(symbol: String): Boolean {
        return compliantSymbols.contains(symbol.uppercase())
    }

    /**
     * Checks if an Angel One token is Shariah compliant.
     * O(1) performance.
     */
    fun isCompliantToken(token: String): Boolean {
        return compliantTokens.contains(token)
    }

    /**
     * Get the size of the current halal universe.
     */
    fun size(): Int = compliantSymbols.size
    
    /**
     * Get all compliant symbols.
     */
    fun getAllCompliantSymbols(): Set<String> {
        return compliantSymbols.toSet()
    }
}
