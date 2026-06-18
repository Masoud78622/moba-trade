package com.mobatrade.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

class RateLimiter(private val maxRequestsPerSecond: Int) {
    private val semaphore = Semaphore(maxRequestsPerSecond)
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        scope.launch {
            while (isActive) {
                delay(1000)
                repeat(maxRequestsPerSecond - semaphore.availablePermits) { semaphore.release() }
            }
        }
    }

    suspend fun <T> execute(block: suspend () -> T): T {
        semaphore.acquire()
        return block()
    }
}
