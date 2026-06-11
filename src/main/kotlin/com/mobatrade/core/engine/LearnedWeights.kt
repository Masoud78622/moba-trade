package com.mobatrade.core.engine

import org.json.JSONObject
import java.io.File

object LearnedWeights {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val weightsFile = File(if (isWindows) "c:\\moba trade\\learned_weights.json" else "learned_weights.json")
    private var cache = JSONObject()

    init {
        load()
    }

    @Synchronized
    fun load() {
        if (weightsFile.exists()) {
            try {
                cache = JSONObject(weightsFile.readText())
            } catch (e: Exception) {
                System.err.println("Failed to load learned weights: ${e.message}")
            }
        }
    }

    @Synchronized
    fun getBonus(strategyName: String): Int {
        return cache.optInt(strategyName, 0)
    }

    @Synchronized
    fun addBonus(strategyName: String, amount: Int) {
        val current = getBonus(strategyName)
        val newBonus = (current + amount).coerceAtMost(2) // Max +2 cap
        cache.put(strategyName, newBonus)
        save()
    }

    @Synchronized
    private fun save() {
        weightsFile.writeText(cache.toString(4))
    }
    
    @Synchronized
    fun getReport(): String {
        return cache.toString(4)
    }
}
