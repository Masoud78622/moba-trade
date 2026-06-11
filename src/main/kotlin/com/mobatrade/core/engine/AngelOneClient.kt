package com.mobatrade.core.engine

import com.mobatrade.core.auth.TotpGenerator
import com.mobatrade.core.halal.ShariahFilter
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.Candle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object AngelOneClient {
    private const val BASE_URL = "https://apiconnect.angelone.in"
    const val DEFAULT_API_KEY = "8M5vqGDS"
    const val DEFAULT_TOTP_SECRET = "K336YHYAV6NN5H2DYMPBBZ55NM"
    private const val DEFAULT_SECRET = DEFAULT_TOTP_SECRET
    const val DEFAULT_CLIENT_ID = "AAAC764774"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // Timestamp of last successful login — used to auto-refresh expired JWT
    @Volatile
    private var lastLoginTimeMs: Long = 0L
    // Angel One JWT tokens expire roughly every 24 hours; we refresh proactively after 20h
    private const val TOKEN_REFRESH_INTERVAL_MS = 20 * 60 * 60 * 1000L // 20 hours

    private val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    // Thread-safe session storage
    @Volatile
    var jwtToken: String? = null
        private set

    @Volatile
    var refreshToken: String? = null
        private set

    @Volatile
    var feedToken: String? = null
        private set

    @Volatile
    var activeApiKey: String = DEFAULT_API_KEY
        private set

    @Volatile
    var activeClientId: String = DEFAULT_CLIENT_ID
        private set

    val isLoggedIn: Boolean
        get() = jwtToken != null

    /**
     * Automatically re-logs in if the JWT token is older than 20 hours.
     * Angel One JWT tokens expire ~24 hours after issuance.
     * Called at the start of every authenticated request as a safety gate.
     */
    @Synchronized
    private fun refreshSessionIfNeeded() {
        val now = System.currentTimeMillis()
        if (jwtToken == null || (now - lastLoginTimeMs) > TOKEN_REFRESH_INTERVAL_MS) {
            println("[SESSION] JWT expired or missing. Auto-refreshing Angel One session...")
            val clientId = System.getenv("ANGEL_CLIENT_ID") ?: activeClientId
            val apiKey = System.getenv("ANGEL_API_KEY") ?: activeApiKey
            val pin = System.getenv("ANGEL_PIN") ?: "3112"
            val totpSecret = System.getenv("ANGEL_TOTP_SECRET") ?: DEFAULT_TOTP_SECRET

            val success = login(
                clientId = clientId,
                tradingPassword = pin,
                apiKey = apiKey,
                totpSecret = totpSecret
            )
            if (success) {
                println("[SESSION] Auto-refresh successful.")
            } else {
                System.err.println("[SESSION] Auto-refresh FAILED. Trades will be blocked until login succeeds.")
            }
        }
    }

    @Synchronized
    fun ensureAuthenticated(): Boolean {
        refreshSessionIfNeeded()
        return isLoggedIn
    }

    /**
     * Programmatically logs in to the Angel One SmartAPI using client credentials and TOTP.
     * Generates a 6-digit dynamic TOTP token on-the-fly.
     *
     * @param tradingPassword Your trading PIN/password. Defaults to a dummy or customizable value.
     */
    @Synchronized
    fun login(
        clientId: String = DEFAULT_CLIENT_ID,
        tradingPassword: String = "3112",
        apiKey: String = DEFAULT_API_KEY,
        totpSecret: String = DEFAULT_SECRET
    ): Boolean {
        activeClientId = clientId
        activeApiKey = apiKey
        try {
            // Generate standard RFC 6238 TOTP
            val totpCode = TotpGenerator.generateTOTP(totpSecret)
            
            val requestBodyJson = JSONObject()
            requestBodyJson.put("clientcode", clientId)
            requestBodyJson.put("password", tradingPassword)
            requestBodyJson.put("totp", totpCode)

            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url("$BASE_URL/rest/auth/angelbroking/user/v1/loginByPassword")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("X-PrivateKey", apiKey)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "192.168.1.100")
                .addHeader("X-ClientPublicIP", "106.193.147.98")
                .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    System.err.println("Angel One Login failed: HTTP ${response.code}")
                    logout()
                    return false
                }

                val bodyStr = response.body?.string() ?: ""
                if (bodyStr.isEmpty()) {
                    logout()
                    return false
                }

                val responseJson = JSONObject(bodyStr)
                if (responseJson.optBoolean("status", false)) {
                    val data = responseJson.optJSONObject("data")
                    if (data != null) {
                        jwtToken = data.optString("jwtToken", null)
                        refreshToken = data.optString("refreshToken", null)
                        feedToken = data.optString("feedToken", null)
                        lastLoginTimeMs = System.currentTimeMillis()
                        println("Angel One session created successfully.")
                        return true
                    }
                } else {
                    System.err.println("Angel One Auth API Error: ${responseJson.optString("message")}")
                    logout()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            logout()
        }
        return false
    }

    /**
     * Submits an order to Angel One SmartAPI after double-checking Shariah compliance.
     * Enforces long-only, delivery-only parameters.
     *
     * @param order Internal order structure representing the trade setup.
     * @param symbolToken Official scrip token mapped for this symbol.
     * @return String? Containing the placed orderId, or null if the order failed or was blocked.
     */
    fun placeOrder(
        order: Order,
        symbolToken: String,
        apiKey: String = DEFAULT_API_KEY,
        isRetry: Boolean = false
    ): String? {
        refreshSessionIfNeeded()
        // Token Integrity Guard QA Layer
        val verifiedToken = TokenIntegrityGuard.verifyAndGetToken(order.symbol, symbolToken)
        if (verifiedToken == null) {
            System.err.println("BLOCK: Order for ${order.symbol} blocked. Token integrity failed (Symbol not found in Scrip Master).")
            return null
        }

        // Shariah Guard: Double-check that this token is compliant
        if (!ShariahFilter.isCompliantToken(verifiedToken) && !ShariahFilter.isCompliantSymbol(order.symbol)) {
            System.err.println("BLOCK: Order for ${order.symbol} (${verifiedToken}) blocked. Asset is NOT Shariah-compliant!")
            return null
        }

        // Shariah Constraints: Long-only, delivery-only, no leverage
        if (order.direction == Direction.SELL && order.quantity < 0) {
            System.err.println("BLOCK: Short selling is strictly prohibited under Shariah guidelines.")
            return null
        }

        val token = jwtToken
        if (token == null) {
            System.err.println("BLOCK: Angel One order failed. Session is not authenticated.")
            return null
        }

        try {
            val requestBodyJson = JSONObject()
            requestBodyJson.put("variety", "NORMAL")
            
            // Map our symbol representation using the Token Integrity Guard's exact trading symbol
            val info = TokenIntegrityGuard.getTokenInfoForSymbol(order.symbol)
            val tradingSymbol = info?.second ?: if (order.symbol.contains("-EQ")) order.symbol else "${order.symbol}-EQ"
            requestBodyJson.put("tradingsymbol", tradingSymbol)
            requestBodyJson.put("symboltoken", verifiedToken)
            requestBodyJson.put("transactiontype", order.direction.name)
            requestBodyJson.put("exchange", "NSE")
            requestBodyJson.put("ordertype", order.orderType.uppercase())
            
            // Strictly Shariah-compliant cash delivery: "DELIVERY" (CNC)
            requestBodyJson.put("producttype", "DELIVERY")
            requestBodyJson.put("duration", "DAY")
            
            // For market orders, the price is set to "0" per SmartAPI specs
            val orderPrice = if (order.orderType.uppercase() == "MARKET") "0" else order.price.toString()
            requestBodyJson.put("price", orderPrice)
            requestBodyJson.put("quantity", order.quantity.toString())

            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/order/v1/placeOrder")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("X-PrivateKey", activeApiKey)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "192.168.1.100")
                .addHeader("X-ClientPublicIP", "106.193.147.98")
                .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    System.err.println("Order placement failed: HTTP ${response.code}")
                    return null
                }

                val bodyStr = response.body?.string() ?: ""
                if (bodyStr.isEmpty()) return null

                val responseJson = JSONObject(bodyStr)
                if (responseJson.optBoolean("status", false)) {
                    val data = responseJson.optJSONObject("data")
                    val orderId = data?.optString("orderid")
                    println("Order placed successfully on Angel One. OrderID: $orderId")
                    return orderId
                } else {
                    val errMsg = responseJson.optString("message")
                    System.err.println("Angel One Order API Error: $errMsg")
                    
                    // Self-Healing Logic for AB1019 Mismatch
                    if (errMsg.contains("AB1019") && !isRetry) {
                        System.err.println("AB1019 Detected for ${order.symbol}. Refreshing Scrip Master and retrying...")
                        TokenIntegrityGuard.forceRefresh()
                        return placeOrder(order, verifiedToken, apiKey, true)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Fetches real-world historical candle data directly from Angel One SmartAPI getIntervalData.
     * Generates a precise fromdate and todate in Asia/Kolkata (IST) timezone.
     *
     * @param symbolToken Official scrip token mapped for this symbol.
     * @param symbol The stock ticker symbol.
     * @param interval Time interval step (defaults to "ONE_HOUR").
     * @param limitDays Number of historical days to pull.
     * @return List<Candle> Containing the parsed candles, or empty list on failure.
     */
    fun fetchHistoricalCandles(
        symbolToken: String,
        symbol: String,
        interval: String = "ONE_HOUR",
        limitDays: Int = 15,
        apiKey: String = DEFAULT_API_KEY
    ): List<Candle> {
        // Auto-refresh session if JWT has expired
        refreshSessionIfNeeded()

        val token = jwtToken
        if (token == null) {
            System.err.println("BLOCK: Angel One historical fetch failed. Session is not authenticated.")
            return emptyList()
        }

        try {
            // Generate standard yyyy-MM-dd HH:mm formats in Asia/Kolkata (IST)
            val zone = ZoneId.of("Asia/Kolkata")
            val nowIst = ZonedDateTime.now(zone)
            val fromIst = nowIst.minusDays(limitDays.toLong())

            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val fromDateStr = fromIst.format(formatter)
            val toDateStr = nowIst.format(formatter)

            val requestBodyJson = JSONObject()
            requestBodyJson.put("exchange", "NSE")
            requestBodyJson.put("symboltoken", symbolToken)
            requestBodyJson.put("interval", interval)
            requestBodyJson.put("fromdate", fromDateStr)
            requestBodyJson.put("todate", toDateStr)

            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/historical/v1/getCandleData")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("X-PrivateKey", activeApiKey)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "192.168.1.100")
                .addHeader("X-ClientPublicIP", "106.193.147.98")
                .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                .build()

            var attempt = 0
            val maxAttempts = 3
            while (attempt < maxAttempts) {
                attempt++
                try {
                    // Build a fresh request each iteration — OkHttp body is single-use
                    val freshToken = jwtToken ?: return emptyList()
                    val retryRequest = Request.Builder()
                        .url("$BASE_URL/rest/secure/angelbroking/historical/v1/getCandleData")
                        .post(requestBody)
                        .addHeader("Authorization", "Bearer $freshToken")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .addHeader("X-PrivateKey", activeApiKey)
                        .addHeader("X-UserType", "USER")
                        .addHeader("X-SourceID", "WEB")
                        .addHeader("X-ClientLocalIP", "192.168.1.100")
                        .addHeader("X-ClientPublicIP", "106.193.147.98")
                        .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                        .build()

                    httpClient.newCall(retryRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string() ?: ""
                            if (bodyStr.isEmpty()) return emptyList()

                            val responseJson = JSONObject(bodyStr)
                            if (responseJson.optBoolean("status", false)) {
                                val dataArray = responseJson.optJSONArray("data") ?: return emptyList()
                                val candles = ArrayList<Candle>()

                                for (i in 0 until dataArray.length()) {
                                    val row = dataArray.getJSONArray(i)
                                    val timestampStr = row.getString(0)
                                    val timestamp = try {
                                        OffsetDateTime.parse(timestampStr).toInstant()
                                    } catch (e: Exception) {
                                        Instant.now().minus((dataArray.length() - i).toLong(), ChronoUnit.HOURS)
                                    }
                                    val open = row.getDouble(1)
                                    val high = row.getDouble(2)
                                    val low = row.getDouble(3)
                                    val close = row.getDouble(4)
                                    val volume = row.getLong(5)
                                    candles.add(Candle(timestamp, open, high, low, close, volume))
                                }
                                return candles
                            } else {
                                val errMsg = responseJson.optString("message", "")
                                System.err.println("Angel One Historical API Error for $symbol: $errMsg")
                                // If session expired (errorcode AB8050 or similar), force a refresh and retry
                                if (errMsg.contains("Invalid Token", ignoreCase = true) ||
                                    errMsg.contains("session", ignoreCase = true) ||
                                    errMsg.contains("expired", ignoreCase = true)) {
                                    System.err.println("[SESSION] Token rejected during candle fetch. Forcing re-login...")
                                    login(
                                        clientId = activeClientId,
                                        tradingPassword = System.getenv("ANGEL_PIN") ?: "3112",
                                        apiKey = activeApiKey,
                                        totpSecret = System.getenv("ANGEL_TOTP_SECRET") ?: DEFAULT_TOTP_SECRET
                                    )
                                    try { Thread.sleep(2000) } catch (ie: InterruptedException) {}
                                } else {
                                    return emptyList()
                                }
                            }
                        } else {
                            val errBody = response.body?.string() ?: ""
                            System.err.println("Historical fetch failed for $symbol ($symbolToken) (Attempt $attempt/$maxAttempts): HTTP ${response.code} - Body: $errBody")
                            if (response.code == 403 && errBody.contains("exceeding access rate")) {
                                println("Rate limit hit for $symbol! Sleeping 10 seconds to cool down before retry...")
                                try { Thread.sleep(10000) } catch (e: InterruptedException) {}
                                // Continue loop to retry
                            } else if (response.code == 401 || response.code == 403) {
                                // Auth failure: re-login and retry
                                System.err.println("[SESSION] Auth error on candle fetch (HTTP ${response.code}). Re-logging in...")
                                login(
                                    clientId = activeClientId,
                                    tradingPassword = System.getenv("ANGEL_PIN") ?: "3112",
                                    apiKey = activeApiKey,
                                    totpSecret = System.getenv("ANGEL_TOTP_SECRET") ?: DEFAULT_TOTP_SECRET
                                )
                                try { Thread.sleep(3000) } catch (ie: InterruptedException) {}
                            } else {
                                return emptyList()
                            }
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("Exception in historical fetch for $symbol: ${e.message}")
                    if (attempt >= maxAttempts) {
                        return emptyList()
                    }
                    try { Thread.sleep(2000) } catch (ie: InterruptedException) {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return emptyList()
    }

    /**
     * Fetches the available margin capital from getRMS.
     */
    fun fetchMarginCapital(): Double {
        refreshSessionIfNeeded()
        val token = jwtToken ?: return 0.0
        try {
            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/user/v1/getRMS")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("X-PrivateKey", activeApiKey)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "192.168.1.100")
                .addHeader("X-ClientPublicIP", "106.193.147.98")
                .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val responseJson = JSONObject(bodyStr)
                    if (responseJson.optBoolean("status", false)) {
                        val data = responseJson.optJSONObject("data")
                        if (data != null) {
                            val netStr = data.optString("net", null) ?:
                                         data.optString("availablecash", null) ?:
                                         data.optString("availablemargin", "0.0")
                            return netStr.toDoubleOrNull() ?: 0.0
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("fetchMarginCapital failed: ${e.message}")
        }
        return 0.0
    }

    fun fetchRealLtp(symbol: String, tokenOverride: String? = null): Double {
        refreshSessionIfNeeded()
        val token = jwtToken ?: return 0.0
        try {
            val effToken = tokenOverride ?: TokenIntegrityGuard.verifyAndGetToken(symbol, null) ?: return 0.0
            val info = TokenIntegrityGuard.getTokenInfoForSymbol(symbol)
            val tradingSymbol = info?.second ?: if (symbol.contains("-EQ")) symbol else "$symbol-EQ"
            
            val requestBodyJson = JSONObject()
            requestBodyJson.put("exchange", "NSE")
            requestBodyJson.put("tradingsymbol", tradingSymbol)
            requestBodyJson.put("symboltoken", effToken)
            
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/order/v1/getLtpData")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("X-PrivateKey", activeApiKey)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "192.168.1.100")
                .addHeader("X-ClientPublicIP", "106.193.147.98")
                .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val responseJson = JSONObject(bodyStr)
                    if (responseJson.optBoolean("status", false)) {
                        val data = responseJson.optJSONObject("data")
                        return data?.optDouble("ltp", 0.0) ?: 0.0
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("fetchRealLtp failed: ${e.message}")
        }
        return 0.0
    }

    /**
     * Fetches active intraday positions.
     */
    fun fetchActivePositions(): List<JSONObject> {
        refreshSessionIfNeeded()
        val token = jwtToken ?: return emptyList()
        val results = ArrayList<JSONObject>()
        try {
            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/order/v1/getPosition")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("X-PrivateKey", activeApiKey)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "192.168.1.100")
                .addHeader("X-ClientPublicIP", "106.193.147.98")
                .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val responseJson = JSONObject(bodyStr)
                    if (responseJson.optBoolean("status", false)) {
                        val dataArray = responseJson.optJSONArray("data")
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                // Include all position types (DELIVERY/CNC and INTRADAY/MIS)
                                results.add(item)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("fetchActivePositions failed: ${e.message}")
        }
        return results
    }

    /**
     * Fetches delivery swing holdings.
     */
    fun fetchSwingHoldings(): List<JSONObject> {
        refreshSessionIfNeeded()
        val token = jwtToken ?: return emptyList()
        val results = ArrayList<JSONObject>()
        try {
            val request = Request.Builder()
                .url("$BASE_URL/rest/secure/angelbroking/portfolio/v1/getHolding")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("X-PrivateKey", activeApiKey)
                .addHeader("X-UserType", "USER")
                .addHeader("X-SourceID", "WEB")
                .addHeader("X-ClientLocalIP", "192.168.1.100")
                .addHeader("X-ClientPublicIP", "106.193.147.98")
                .addHeader("X-MACAddress", "00-50-56-C0-00-08")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val responseJson = JSONObject(bodyStr)
                    if (responseJson.optBoolean("status", false)) {
                        val dataArray = responseJson.optJSONArray("data")
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                results.add(dataArray.getJSONObject(i))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("fetchSwingHoldings failed: ${e.message}")
        }
        return results
    }

    /**
     * Resets active session credentials (useful for testing/invalidation)
     */
    @Synchronized
    fun logout() {
        jwtToken = null
        refreshToken = null
        feedToken = null
    }
}
