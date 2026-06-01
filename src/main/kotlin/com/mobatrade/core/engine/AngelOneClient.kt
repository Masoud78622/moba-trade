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
    private const val DEFAULT_API_KEY = "8M5vqGDS"
    private const val DEFAULT_SECRET = "133ea263-b945-47a1-a718-9d063fecd674"
    private const val DEFAULT_CLIENT_ID = "AAAC764774"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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

    val isLoggedIn: Boolean
        get() = jwtToken != null

    /**
     * Programmatically logs in to the Angel One SmartAPI using client credentials and TOTP.
     * Generates a 6-digit dynamic TOTP token on-the-fly.
     *
     * @param tradingPassword Your trading PIN/password. Defaults to a dummy or customizable value.
     */
    @Synchronized
    fun login(
        clientId: String = DEFAULT_CLIENT_ID,
        tradingPassword: String = "123456",
        apiKey: String = DEFAULT_API_KEY,
        totpSecret: String = DEFAULT_SECRET
    ): Boolean {
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
                    return false
                }

                val bodyStr = response.body?.string() ?: ""
                if (bodyStr.isEmpty()) return false

                val responseJson = JSONObject(bodyStr)
                if (responseJson.optBoolean("status", false)) {
                    val data = responseJson.optJSONObject("data")
                    if (data != null) {
                        jwtToken = data.optString("jwtToken", null)
                        refreshToken = data.optString("refreshToken", null)
                        feedToken = data.optString("feedToken", null)
                        println("Angel One session created successfully.")
                        return true
                    }
                } else {
                    System.err.println("Angel One Auth API Error: ${responseJson.optString("message")}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        apiKey: String = DEFAULT_API_KEY
    ): String? {
        // Shariah Guard: Double-check that this token is compliant
        if (!ShariahFilter.isCompliantToken(symbolToken) && !ShariahFilter.isCompliantSymbol(order.symbol)) {
            System.err.println("BLOCK: Order for ${order.symbol} (${symbolToken}) blocked. Asset is NOT Shariah-compliant!")
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
            
            // Map our symbol representation (e.g., TCS to TCS-EQ for Angel One)
            val tradingSymbol = if (order.symbol.contains("-EQ")) order.symbol else "${order.symbol}-EQ"
            requestBodyJson.put("tradingsymbol", tradingSymbol)
            requestBodyJson.put("symboltoken", symbolToken)
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
                .addHeader("X-PrivateKey", apiKey)
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
                    System.err.println("Angel One Order API Error: ${responseJson.optString("message")}")
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
                .url("$BASE_URL/rest/secure/angelbroking/historical/v1/getIntervalData")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
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
                    System.err.println("Historical fetch failed: HTTP ${response.code}")
                    return emptyList()
                }

                val bodyStr = response.body?.string() ?: ""
                if (bodyStr.isEmpty()) return emptyList()

                val responseJson = JSONObject(bodyStr)
                if (responseJson.optBoolean("status", false)) {
                    val dataArray = responseJson.optJSONArray("data") ?: return emptyList()
                    val candles = ArrayList<Candle>()

                    for (i in 0 until dataArray.length()) {
                        val row = dataArray.getJSONArray(i)
                        val timestampStr = row.getString(0)

                        // Parse timestamp (ISO-8601 offset like '2026-06-01T09:15:00+05:30')
                        val timestamp = try {
                            OffsetDateTime.parse(timestampStr).toInstant()
                        } catch (e: Exception) {
                            // Fallback incremental timestamp
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
                    System.err.println("Angel One Historical API Error: ${responseJson.optString("message")}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return emptyList()
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
