package com.mobatrade.core.engine

import java.io.File

object EnvLoader {
    private val envMap = HashMap<String, String>()

    init {
        load()
    }

    private fun load() {
        try {
            val file = File(".env")
            if (file.exists()) {
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        val key = trimmed.substringBefore("=").trim()
                        val value = trimmed.substringAfter("=").trim()
                        envMap[key] = value
                    }
                }
                println("EnvLoader: Loaded local .env file successfully.")
            }
        } catch (e: Exception) {
            System.err.println("EnvLoader: Failed to load .env file: ${e.message}")
        }
    }

    fun get(key: String): String? {
        return envMap[key] ?: System.getenv(key)
    }
}
