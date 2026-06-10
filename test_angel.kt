import com.mobatrade.core.engine.*

fun main() {
    println(Attempting login...)
    val success = AngelOneClient.login(
        clientId = AngelOneClient.DEFAULT_CLIENT_ID,
        tradingPassword = 3112,
        apiKey = AngelOneClient.DEFAULT_API_KEY,
        totpSecret = AngelOneClient.DEFAULT_TOTP_SECRET
    )
    println(Login success: $success)
    if(success) {
        val totalCapital = AngelOneClient.fetchMarginCapital()
        println(Capital: $totalCapital)
    }
}
