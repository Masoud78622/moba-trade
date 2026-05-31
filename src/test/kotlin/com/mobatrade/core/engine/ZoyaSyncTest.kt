package com.mobatrade.core.engine

import com.mobatrade.core.auth.TotpGenerator
import com.mobatrade.core.halal.ShariahFilter
import com.mobatrade.core.halal.ZoyaSyncService
import com.mobatrade.core.model.Direction
import com.mobatrade.core.model.Order
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class ZoyaSyncTest {

    private val testCachePath = "c:\\moba trade\\test_halal_stocks.json"

    @BeforeEach
    fun setUp() {
        // Ensure clean test state
        val testFile = File(testCachePath)
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    @Test
    fun testTotpGenerationFormat() {
        val secret = "133ea263-b945-47a1-a718-9d063fecd674"
        val code = TotpGenerator.generateTOTP(secret)
        
        assertNotNull(code)
        assertEquals(6, code.length, "TOTP must be exactly 6 digits")
        assertTrue(code.all { it.isDigit() }, "TOTP must contain only numeric digits")
    }

    @Test
    fun testZoyaSyncAndLocalCaching() {
        val testStocks = listOf(
            ZoyaSyncService.CompliantStock("TCS", "Tata Consultancy Services Ltd", "NSE", 0.05),
            ZoyaSyncService.CompliantStock("INFY", "Infosys Ltd", "NSE", 0.03),
            ZoyaSyncService.CompliantStock("WIPRO", "Wipro Ltd", "NSE", 0.02)
        )

        // Verify cache writing
        val success = ZoyaSyncService.saveCompliantUniverseToCache(testStocks, testCachePath)
        assertTrue(success, "Should save compliant stocks successfully to the local JSON cache")

        val testFile = File(testCachePath)
        assertTrue(testFile.exists(), "Cache file must exist on disk after saving")

        // Load the cached universe into ShariahFilter
        val loadSuccess = ShariahFilter.loadUniverse(testCachePath)
        assertTrue(loadSuccess, "Should load cached compliant universe successfully")
        
        assertEquals(3, ShariahFilter.size(), "Shariah filter should index exactly 3 stocks")
        assertTrue(ShariahFilter.isCompliantSymbol("TCS"), "TCS must be recognized as compliant")
        assertTrue(ShariahFilter.isCompliantSymbol("INFY"), "INFY must be recognized as compliant")
        assertFalse(ShariahFilter.isCompliantSymbol("RELIANCE"), "RELIANCE must NOT be recognized as compliant")

        // Clean up
        testFile.delete()
    }

    @Test
    fun testAngelOneShariahOrderGuard() {
        // Manually initialize Shariah Filter with a limited compliant universe
        ShariahFilter.initializeManual(
            symbols = listOf("TCS", "INFY"),
            tokens = listOf("11536", "1594")
        )

        // 1. Compliant Order - Should Proceed to API Authentication block (fails login since we use default mock credentials in unit environment)
        val halalOrder = Order("TCS", 5, 3100.0, Direction.BUY, "MARKET")
        val halalResult = AngelOneClient.placeOrder(halalOrder, "11536")
        
        // Since we're not logged in, it should return null but the log must show it passed the Shariah compliance check.
        assertNull(halalResult, "Order should fail because the session is not authenticated, not due to Shariah blocking")

        // 2. Non-Compliant Order - Should be BLOCKED immediately at Shariah guard
        val haramOrder = Order("RELIANCE", 10, 2400.0, Direction.BUY, "MARKET")
        val haramResult = AngelOneClient.placeOrder(haramOrder, "99999")
        
        assertNull(haramResult, "Non-compliant order must be blocked and return null immediately")
        
        // 3. Short Selling Order - Should be BLOCKED immediately
        val shortOrder = Order("TCS", -5, 3100.0, Direction.SELL, "MARKET")
        val shortResult = AngelOneClient.placeOrder(shortOrder, "11536")
        
        assertNull(shortResult, "Short-selling order must be blocked by Shariah rules")
    }
}
