package com.mobatrade.core.engine

import com.mobatrade.core.halal.ShariahFilter

fun main() {
    println("Forcing Zoya Sync...")
    val apiKey = "sandbox-4a7b2ac0-4490-4e2b-9933-3e2dcc78a354"
    val success = ShariahFilter.initializeWithSync(apiKey)
    if (success) {
        println("Sync successful! Current universe size: ${ShariahFilter.size()}")
    } else {
        println("Sync failed.")
    }
}
