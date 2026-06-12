package com.mobatrade.core.engine

import okhttp3.OkHttpClient
import okhttp3.Credentials
import java.net.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

object HttpClientFactory {
    fun createClient(
        connectTimeoutSec: Long = 20,
        readTimeoutSec: Long = 30,
        writeTimeoutSec: Long = 20
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)

        val proxyHost = EnvLoader.get("PROXY_HOST")
        val proxyPortStr = EnvLoader.get("PROXY_PORT")
        
        if (!proxyHost.isNullOrBlank() && !proxyPortStr.isNullOrBlank()) {
            val proxyPort = proxyPortStr.toIntOrNull() ?: 8080
            val proxyTypeStr = EnvLoader.get("PROXY_TYPE") ?: "HTTP"
            val proxyType = if (proxyTypeStr.equals("SOCKS", ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP
            
            println("HttpClientFactory: Configuring $proxyType proxy: $proxyHost:$proxyPort")
            try {
                val proxy = Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort))
                builder.proxy(proxy)

                val proxyUser = EnvLoader.get("PROXY_USER")
                val proxyPass = EnvLoader.get("PROXY_PASSWORD")
                if (!proxyUser.isNullOrBlank() && !proxyPass.isNullOrBlank()) {
                    builder.proxyAuthenticator { _, response ->
                        println("HttpClientFactory: Providing basic auth credentials for proxy.")
                        val credential = Credentials.basic(proxyUser, proxyPass)
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                }
            } catch (e: Exception) {
                System.err.println("HttpClientFactory: Failed to initialize proxy configuration: ${e.message}")
            }
        }
        return builder.build()
    }
}
