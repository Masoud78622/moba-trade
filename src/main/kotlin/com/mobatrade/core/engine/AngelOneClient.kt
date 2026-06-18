package com.mobatrade.core.engine

import com.mobatrade.core.auth.TotpGenerator
import com.mobatrade.core.halal.ShariahFilter
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import com.mobatrade.core.model.Candle
import com.mobatrade.core.model.FetchResult
import com.mobatrade.core.model.OrderResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketTimeoutException
import java.io.IOException
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

    private val historicalDataLimiter = RateLimiter(maxRequestsPerSecond = 3)
    private val apiMutex = Mutex()

    private val httpClient = HttpClientFactory.createClient(20, 30, 20)
    private val historicalHttpClient = httpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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

    @Volatile
    var autoRefreshEnabled: Boolean = true

    val isLoggedIn: Boolean
        get() = jwtToken != null

    private val historicalLock = Any()
    @Volatile
    private var lastHistoricalCallTimeMs = 0L

    /**
     * Automatically re-logs in if the JWT token is older than 20 hours.
     * Angel One JWT tokens expire ~24 hours after issuance.
     * Called at the start of every authenticated request as a safety gate.
     */
    @Synchronized
    private fun refreshSessionIfNeeded() {
        if (!autoRefreshEnabled) return
        val now = System.currentTimeMillis()
        if (jwtToken == null || (now - lastLoginTimeMs) > TOKEN_REFRESH_INTERVAL_MS) {
            println("[SESSION] JWT expired or missing. Auto-refreshing Angel One session...")
            val clientId = EnvLoader.get("ANGEL_CLIENT_ID") ?: activeClientId
            val apiKey = EnvLoader.get("ANGEL_API_KEY") ?: activeApiKey
            val pin = EnvLoader.get("ANGEL_PIN") ?: "3112"
            val totpSecret = EnvLoader.get("ANGEL_TOTP_SECRET") ?: DEFAULT_TOTP_SECRET

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

            var lastException: Exception? = null
            repeat(3) { attempt ->
                try {
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
                    lastException = e
                    System.err.println("Angel One Login attempt ${attempt + 1} failed with network error: ${e.message}")
                    try { Thread.sleep(1000L * (attempt + 1)) } catch (ie: InterruptedException) {}
                }
            }
            
            System.err.println("Angel One Login failed after 3 attempts. Last error: ${lastException?.message}")
            logout()
            return false
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
    suspend fun placeOrder(
        order: Order,
        symbolToken: String,
        apiKey: String = DEFAULT_API_KEY,
        maxRetries: Int = 2
    ): com.mobatrade.core.model.OrderResult {
        refreshSessionIfNeeded()
        // Token Integrity Guard QA Layer
        val verifiedToken = TokenIntegrityGuard.verifyAndGetToken(order.symbol, symbolToken)
        if (verifiedToken == null) {
            System.err.println("BLOCK: Order for ${order.symbol} blocked. Token integrity failed (Symbol not found in Scrip Master).")
            return com.mobatrade.core.model.OrderResult.Failure("Token integrity failed")
        }

        // Shariah Guard: Double-check that this token is compliant (ONLY for BUY orders)
        if (order.direction == Direction.BUY && !ShariahFilter.isCompliantToken(verifiedToken) && !ShariahFilter.isCompliantSymbol(order.symbol)) {
            System.err.println("BLOCK: Order for ${order.symbol} (${verifiedToken}) blocked. Asset is NOT Shariah-compliant!")
            return com.mobatrade.core.model.OrderResult.Failure("Not Shariah-compliant")
        }

        // Shariah Constraints: Long-only, delivery-only, no leverage
        if (order.direction == Direction.SELL && order.quantity < 0) {
            System.err.println("BLOCK: Short selling is strictly prohibited under Shariah guidelines.")
            return com.mobatrade.core.model.OrderResult.Failure("Short selling prohibited")
        }

        val token = jwtToken
        if (token == null) {
            System.err.println("BLOCK: Angel One order failed. Session is not authenticated.")
            return com.mobatrade.core.model.OrderResult.Failure("Session is not authenticated")
        }

        repeat(maxRetries) { attempt ->
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
                        return@use
                    }

                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isEmpty()) return@use

                    val responseJson = JSONObject(bodyStr)
                    if (responseJson.optBoolean("status", false)) {
                        val data = responseJson.optJSONObject("data")
                        val orderId = data?.optString("orderid")
                        println("Order placed successfully on Angel One. OrderID: $orderId")
                        if (orderId != null) return com.mobatrade.core.model.OrderResult.Success(orderId)
                    } else {
                        val errMsg = responseJson.optString("message")
                        val errCode = responseJson.optString("errorcode")
                        System.err.println("Angel One Order API Error: $errMsg (Code: $errCode)")
                        
                        // GSM/ASM or Cautionary fallback: retry as LIMIT at LTP
                        val isCautionaryOrGsm = errCode == "AB4036" || 
                            errMsg.contains("cautionary", ignoreCase = true) ||
                            errMsg.contains("GSM", ignoreCase = true) ||
                            errMsg.contains("ASM", ignoreCase = true) ||
                            errMsg.contains("circuit", ignoreCase = true)

                        if (isCautionaryOrGsm) {
                            System.err.println("⚠️ GSM/ASM/Cautionary listing detected for ${order.symbol}. Retrying as LIMIT order at LTP.")
                            val ltp = fetchRealLtp(order.symbol, verifiedToken)
                            if (ltp > 0.0) {
                                val roundedLtp = Math.round(ltp * 20.0) / 20.0
                                println("🤖 Fetched LTP for fallback limit order: ₹$roundedLtp")
                                val limitOrder = order.copy(orderType = "LIMIT", price = roundedLtp)
                                return placeOrder(limitOrder, verifiedToken, apiKey, 1) // only 1 fallback attempt
                            } else {
                                System.err.println("❌ Failed to fetch LTP for cautionary/GSM stock fallback.")
                            }
                        }

                        // Self-Healing Logic for AB1019 Mismatch
                        if (errMsg.contains("AB1019")) {
                            System.err.println("AB1019 Detected for ${order.symbol}. Refreshing Scrip Master and retrying...")
                            TokenIntegrityGuard.forceRefresh()
                            if (attempt == maxRetries - 1) return com.mobatrade.core.model.OrderResult.Failure("Persistent AB1019")
                            return@use
                        }
                        
                        return com.mobatrade.core.model.OrderResult.Failure(errCode)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                System.err.println("Timeout placing order for ${order.symbol}")
            } catch (e: java.io.IOException) {
                System.err.println("IO Error placing order for ${order.symbol}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return com.mobatrade.core.model.OrderResult.Failure("Max retries exceeded")
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
    suspend fun <T> withAuthRetry(maxAttempts: Int = 1, block: suspend () -> FetchResult<T>): FetchResult<T> {
        var attempts = 0
        while (true) {
            val result = block()
            if (result is FetchResult.Success) return result
            
            val failure = result as FetchResult.Failure
            if (failure.reason != "auth_error" || attempts >= maxAttempts) {
                if (failure.reason == "auth_error") {
                    System.err.println("[SESSION] Re-authentication failed — trading halted temporarily.")
                }
                return result
            }
            attempts++
            System.err.println("[SESSION] Auth rejected, re-authenticating (attempt $attempts)...")
            val reauthed = login(
                clientId = EnvLoader.get("ANGEL_CLIENT_ID") ?: activeClientId,
                tradingPassword = EnvLoader.get("ANGEL_PIN") ?: "3112",
                apiKey = EnvLoader.get("ANGEL_API_KEY") ?: activeApiKey,
                totpSecret = EnvLoader.get("ANGEL_TOTP_SECRET") ?: DEFAULT_TOTP_SECRET
            )
            if (!reauthed) {
                System.err.println("[SESSION] Re-login failed — returning auth_error")
                return FetchResult.Failure("auth_error")
            }
            delay(1000L)
        }
    }

    suspend fun fetchHistoricalCandlesCore(
        symbolToken: String,
        symbol: String,
        interval: String = "ONE_HOUR",
        limitDays: Int = 15,
        apiKey: String = DEFAULT_API_KEY
    ): FetchResult<List<Candle>> {
        return historicalDataLimiter.execute {
            refreshSessionIfNeeded()
            val token = jwtToken ?: return@execute FetchResult.Failure("auth_error")

            try {
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

                historicalHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        if (bodyStr.isEmpty()) return@execute FetchResult.Success(emptyList())

                        val responseJson = JSONObject(bodyStr)
                        if (responseJson.optBoolean("status", false)) {
                            val dataArray = responseJson.optJSONArray("data") ?: return@execute FetchResult.Success(emptyList())
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
                            return@execute FetchResult.Success(candles)
                        } else {
                            val errMsg = responseJson.optString("message", "")
                            if (errMsg.contains("Invalid Token", ignoreCase = true) ||
                                errMsg.contains("session", ignoreCase = true) ||
                                errMsg.contains("expired", ignoreCase = true)) {
                                return@execute FetchResult.Failure("auth_error")
                            }
                            return@execute FetchResult.Failure(errMsg)
                        }
                    } else {
                        val errBody = response.body?.string() ?: ""
                        if (response.code == 403 && errBody.contains("exceeding access rate")) {
                            return@execute FetchResult.Failure("rate_limit")
                        } else if (response.code == 401 || response.code == 403) {
                            return@execute FetchResult.Failure("auth_error")
                        }
                        return@execute FetchResult.Failure("HTTP ${response.code}")
                    }
                }
            } catch (e: SocketTimeoutException) {
                return@execute FetchResult.Failure("timeout")
            } catch (e: IOException) {
                return@execute FetchResult.Failure("io_error")
            } catch (e: Exception) {
                return@execute FetchResult.Failure("exception")
            }
        }
    }

    suspend fun fetchHistoricalCandles(
        symbolToken: String,
        symbol: String,
        interval: String = "ONE_HOUR",
        limitDays: Int = 15,
        apiKey: String = DEFAULT_API_KEY
    ): FetchResult<List<Candle>> {
        return withAuthRetry(maxAttempts = 1) {
            apiMutex.withLock {
                var attempts = 0
                while (attempts < 3) {
                    val result = fetchHistoricalCandlesCore(symbolToken, symbol, interval, limitDays, apiKey)
                    if (result is FetchResult.Success) return@withAuthRetry result
                
                val failure = result as FetchResult.Failure
                if (failure.reason == "auth_error") {
                    return@withAuthRetry result // Pass up to withAuthRetry
                } else if (failure.reason == "rate_limit") {
                    System.err.println("Rate limit hit for $symbol! Sleeping 2 seconds to cool down before retry...")
                    delay(2000L) // Wait and retry
                } else if (failure.reason == "timeout" || failure.reason == "io_error") {
                    System.err.println("Network issue fetching $symbol: ${failure.reason}. Retrying...")
                    delay(1000L)
                } else {
                    return@withAuthRetry result // Terminal failure
                }
                attempts++
                }
                FetchResult.Failure("Max attempts reached")
            }
        }
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
