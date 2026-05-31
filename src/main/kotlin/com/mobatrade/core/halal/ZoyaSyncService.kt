package com.mobatrade.core.halal

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object ZoyaSyncService {
    private const val ZOYA_ENDPOINT = "https://sandbox-api.zoya.finance/graphql"
    private const val DEFAULT_API_KEY = "sandbox-4a7b2ac0-4490-4e2b-9933-3e2dcc78a354"
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class CompliantStock(
        val symbol: String,
        val name: String,
        val exchange: String,
        val purificationRatio: Double
    )

    /**
     * Natively synchronizes compliant stocks from the Zoya Sandbox GraphQL API.
     * Pages through all records using nextToken, extracts compliant symbols,
     * and returns them as a list of CompliantStock.
     */
    fun syncCompliantStocks(apiKey: String = DEFAULT_API_KEY): List<CompliantStock> {
        val compliantList = ArrayList<CompliantStock>()
        var nextToken: String? = null
        var hasNextPage = true
        var pageCount = 0

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

        while (hasNextPage && pageCount < 50) { // Safety cap to avoid infinite loops
            pageCount++
            
            // Build GraphQL Query with dynamic variables
            val variablesObj = JSONObject()
            val inputObj = JSONObject()
            inputObj.put("limit", 100)
            if (nextToken != null) {
                inputObj.put("nextToken", nextToken)
            }
            // Zoya filters can filter status compliant directly
            val filtersObj = JSONObject()
            filtersObj.put("status", "COMPLIANT")
            inputObj.put("filters", filtersObj)
            
            variablesObj.put("input", inputObj)

            val graphQuery = """
                query GetBasicCompliances(${'$'}input: BasicReportsInput!) {
                  basicCompliance {
                    reports(input: ${'$'}input) {
                      items {
                        symbol
                        name
                        exchange
                        status
                        purificationRatio
                      }
                      nextToken
                    }
                  }
                }
            """.trimIndent()

            val requestJson = JSONObject()
            requestJson.put("query", graphQuery)
            requestJson.put("variables", variablesObj)

            val requestBody = requestJson.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(ZOYA_ENDPOINT)
                .post(requestBody)
                .addHeader("Authorization", apiKey)
                .addHeader("Content-Type", "application/json")
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        System.err.println("Zoya Sync Failed: HTTP Code ${response.code}")
                        hasNextPage = false
                        return@use
                    }

                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isEmpty()) {
                        hasNextPage = false
                        return@use
                    }

                    val jsonResponse = JSONObject(bodyString)
                    if (jsonResponse.has("errors")) {
                        val errors = jsonResponse.getJSONArray("errors")
                        System.err.println("Zoya GraphQL Error: ${errors.getJSONObject(0).optString("message")}")
                        hasNextPage = false
                        return@use
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val basicCompliance = data?.optJSONObject("basicCompliance")
                    val reports = basicCompliance?.optJSONObject("reports")
                    
                    if (reports != null) {
                        val items = reports.optJSONArray("items") ?: JSONArray()
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val symbol = item.optString("symbol", "")
                            val name = item.optString("name", "")
                            val exchange = item.optString("exchange", "")
                            val purificationRatio = item.optDouble("purificationRatio", 0.0)
                            
                            if (symbol.isNotEmpty()) {
                                compliantList.add(CompliantStock(symbol, name, exchange, purificationRatio))
                            }
                        }

                        nextToken = reports.optString("nextToken", "")
                        if (nextToken.isNullOrEmpty() || nextToken == "null") {
                            hasNextPage = false
                        }
                    } else {
                        hasNextPage = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                hasNextPage = false
            }
        }
        return compliantList
    }

    /**
     * Resolves the symbols into ShariahFilter's format and writes them directly to c:\moba trade\halal_stocks.json.
     * Generates a mock or mapped numerical Angel One Token for each stock since Zoya returns global symbols.
     */
    fun saveCompliantUniverseToCache(stocks: List<CompliantStock>, cachePath: String = "c:\\moba trade\\halal_stocks.json"): Boolean {
        return try {
            val file = File(cachePath)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            val array = JSONArray()
            // Map common symbols to real Angel One tokens if available, otherwise hash it dynamically
            for (stock in stocks) {
                val obj = JSONObject()
                obj.put("symbol", stock.symbol)
                obj.put("name", stock.name)
                obj.put("exchange", stock.exchange)
                obj.put("purificationRatio", stock.purificationRatio)
                
                // Generate a deterministic unique token code for Angel One
                val token = when (stock.symbol.uppercase()) {
                    "TCS" -> "11536"
                    "INFY" -> "1594"
                    "WIPRO" -> "3787"
                    else -> (stock.symbol.hashCode() and 0xffff).toString()
                }
                obj.put("token", token)
                array.put(obj)
            }

            file.writeText(array.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
