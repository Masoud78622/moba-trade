package com.mobatrade.core.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object TokenIntegrityGuard {
    private const val SCRIP_MASTER_URL = "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json"
    
    // Store cache in project root
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val CACHE_PATH = if (isWindows) "c:\\moba trade\\scrip_master.json" else "scrip_master.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Base Symbol -> (Token, TradingSymbol)
    // E.g., "MGL" -> Pair("17534", "MGL-EQ") or Pair("17534", "MGL-BE")
    private val symbolMap = HashMap<String, Pair<String, String>>()

    @Volatile
    private var isLoaded = false

    /**
     * Ensures the daily symbol master is downloaded and loaded into memory.
     * Should be called at startup.
     */
    @Synchronized
    fun ensureMasterDownloaded() {
        if (isLoaded) return

        val cacheFile = File(CACHE_PATH)
        val todayStr = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("Asia/Kolkata"))
            .format(Instant.now())

        var needsDownload = true

        if (cacheFile.exists()) {
            val lastModified = Instant.ofEpochMilli(cacheFile.lastModified())
            val fileDateStr = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(lastModified)

            if (fileDateStr == todayStr) {
                println("TokenIntegrityGuard: Scrip Master cache is up-to-date ($todayStr).")
                needsDownload = false
            } else {
                println("TokenIntegrityGuard: Scrip Master cache is outdated ($fileDateStr). Will refresh for $todayStr.")
            }
        }

        if (needsDownload) {
            downloadMasterFile(cacheFile)
        }

        if (cacheFile.exists()) {
            loadIntoMemory(cacheFile)
        } else {
            System.err.println("TokenIntegrityGuard: Failed to load Scrip Master! File does not exist.")
        }
    }

    /**
     * Forces a fresh download of the Scrip Master JSON and reloads memory.
     */
    @Synchronized
    fun forceRefresh() {
        println("TokenIntegrityGuard: Force refreshing Scrip Master...")
        val cacheFile = File(CACHE_PATH)
        downloadMasterFile(cacheFile)
        if (cacheFile.exists()) {
            loadIntoMemory(cacheFile)
        }
    }

    private fun downloadMasterFile(cacheFile: File) {
        println("TokenIntegrityGuard: Downloading latest Angel One Scrip Master (this may take a moment)...")
        try {
            val request = Request.Builder().url(SCRIP_MASTER_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    // Stream body directly to file to prevent loading the entire 35MB array in memory
                    response.body!!.byteStream().use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    println("TokenIntegrityGuard: Downloaded and saved successfully.")
                } else {
                    System.err.println("TokenIntegrityGuard: Download failed with HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            System.err.println("TokenIntegrityGuard: Exception during download - ${e.message}")
        }
    }

    private fun loadIntoMemory(cacheFile: File) {
        try {
            println("TokenIntegrityGuard: Parsing Scrip Master JSON memory-efficiently...")
            symbolMap.clear()
            var count = 0

            cacheFile.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(65536)
                val sb = StringBuilder()
                var inObject = false
                var readLen = reader.read(buffer)

                while (readLen != -1) {
                    for (i in 0 until readLen) {
                        val ch = buffer[i]
                        if (ch == '{') {
                            sb.setLength(0)
                            inObject = true
                            sb.append(ch)
                        } else if (ch == '}') {
                            if (inObject) {
                                sb.append(ch)
                                val objStr = sb.toString()
                                if (objStr.contains("\"exch_seg\":\"NSE\"")) {
                                    try {
                                        val item = JSONObject(objStr)
                                        val symbol = item.optString("symbol")
                                        val token = item.optString("token")
                                        val name = item.optString("name")
                                        
                                        if (name.isNotEmpty() && token.isNotEmpty() && symbol.isNotEmpty()) {
                                            val existing = symbolMap[name]
                                            if (existing == null || symbol.endsWith("-EQ")) {
                                                symbolMap[name] = Pair(token, symbol)
                                                count++
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Ignore individual parsing errors
                                    }
                                }
                                inObject = false
                            }
                        } else if (inObject) {
                            sb.append(ch)
                        }
                    }
                    readLen = reader.read(buffer)
                }
            }

            if (count > 0) {
                isLoaded = true
                println("TokenIntegrityGuard: Loaded $count NSE instruments into memory.")
            } else {
                isLoaded = false
                System.err.println("TokenIntegrityGuard: Map is empty after parsing. Load failed.")
            }
        } catch (e: Exception) {
            isLoaded = false
            System.err.println("TokenIntegrityGuard: Failed to parse Scrip Master - ${e.message}")
        }
    }

    /**
     * Retrieves the authoritative token for a given base symbol (e.g., "MGL").
     * Returns the pair (Token, TradingSymbol) or null if not found.
     */
    fun getTokenInfoForSymbol(baseSymbol: String): Pair<String, String>? {
        if (!isLoaded) ensureMasterDownloaded()
        return symbolMap[baseSymbol.uppercase()]
    }

    /**
     * Checks if a previously cached token matches the current live token from the exchange.
     */
    fun verifyAndGetToken(baseSymbol: String, expectedToken: String?): String? {
        val info = getTokenInfoForSymbol(baseSymbol)
        if (info == null) {
            System.err.println("TokenIntegrityGuard QA: Symbol $baseSymbol NOT FOUND in NSE Scrip Master!")
            return null
        }

        val actualToken = info.first
        val tradingSymbol = info.second

        if (expectedToken != null && expectedToken != actualToken) {
            System.err.println("TokenIntegrityGuard QA: Mismatch detected for $baseSymbol! " +
                    "Expected Token: $expectedToken | Live Token: $actualToken | Segment: $tradingSymbol")
        }

        return actualToken
    }
}
