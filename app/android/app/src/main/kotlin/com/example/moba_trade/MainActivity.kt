package com.example.moba_trade

import android.content.Intent
import android.os.Build
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.mobatrade.core/service"
    private var isServiceRunning = false

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startAutoBot" -> {
                    startMobaService()
                    result.success(true)
                }
                "stopAutoBot" -> {
                    stopMobaService()
                    result.success(true)
                }
                "isAutoBotRunning" -> {
                    result.success(isServiceRunning)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun startMobaService() {
        val intent = Intent(this, MobaTradeService::class.java).apply {
            action = MobaTradeService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isServiceRunning = true
        } catch (e: Exception) {
            android.util.Log.e("MobaTrade", "Failed to start background service: " + e.message)
            isServiceRunning = false
        }
    }

    private fun stopMobaService() {
        val intent = Intent(this, MobaTradeService::class.java).apply {
            action = MobaTradeService.ACTION_STOP
        }
        startService(intent)
        isServiceRunning = false
    }
}
