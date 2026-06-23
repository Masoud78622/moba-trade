package com.mobatrade.core.engine

import java.io.File

object EnvLoader {
    private val envMap = HashMap<String, String>()
    private val requiredKeys = listOf("ANGEL_API_KEY", "ANGEL_CLIENT_ID", "ANGEL_TOTP_SECRET")

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
            println("EnvLoader: Failed to load .env file, falling back to system env vars.")
        }

        val missing = requiredKeys.filter { get(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw java.lang.IllegalStateException("Missing required env vars: $missing — refusing to start")
        }
    }

    fun get(key: String): String? {
        return envMap[key] ?: System.getenv(key)
    }

    fun setForTest(key: String, value: String?) {
        if (value == null) {
            envMap.remove(key)
        } else {
            envMap[key] = value
        }
    }
}
