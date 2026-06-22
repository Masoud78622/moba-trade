package com.mobatrade.core.engine

fun main() {
    println("=========================================================")
    println("        VERIFYING ANGEL ONE API CONNECTION STATUS        ")
    println("=========================================================")
    try {
        println("Loading environment variables...")
        val clientId = EnvLoader.get("ANGEL_CLIENT_ID")
        val apiKey = EnvLoader.get("ANGEL_API_KEY")
        val totpSecret = EnvLoader.get("ANGEL_TOTP_SECRET")
        val pin = EnvLoader.get("ANGEL_PIN") ?: "3112"
        
        println("Client ID: $clientId")
        println("API Key: $apiKey")
        
        if (clientId.isNullOrBlank() || apiKey.isNullOrBlank() || totpSecret.isNullOrBlank()) {
            println("❌ Error: Missing credentials in .env file.")
            return
        }

        println("Attempting login via AngelOneClient...")
        val success = AngelOneClient.login(
            clientId = clientId,
            tradingPassword = pin,
            apiKey = apiKey,
            totpSecret = totpSecret
        )
        println("Login Result: ${if (success) "SUCCESS ✅" else "FAILED ❌"}")
        if (success) {
            val margin = AngelOneClient.fetchMarginCapital()
            println("Successfully retrieved account margin/capital: ₹$margin")
            
            val holdings = AngelOneClient.fetchSwingHoldings()
            println("Successfully connected to holdings: ${holdings.size} active swing positions found.")
        }
    } catch (e: Exception) {
        println("Error during verification: ${e.message}")
        e.printStackTrace()
    }
}
