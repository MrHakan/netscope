package com.netscope.core.network

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Wi-Fi multicast lock.
 *
 * Wi-Fi power saving filters out multicast and broadcast frames, so mDNS, SSDP and
 * LLMNR see nothing without this lock. A leaked multicast lock is a serious battery
 * bug, so the only way to take one is [withLock], which always releases it.
 *
 * Reference counting lets overlapping discoveries share one lock.
 */
@Singleton
class MulticastLockHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val lock = Any()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var holders = 0

    /** Runs [block] with the multicast lock held, releasing it even if [block] throws. */
    inline fun <T> withLock(block: () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
    }

    fun acquire() {
        synchronized(lock) {
            if (holders == 0) {
                val manager = context.applicationContext.getSystemService(WifiManager::class.java)
                multicastLock = runCatching {
                    manager?.createMulticastLock(TAG)?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.getOrNull()
            }
            holders++
        }
    }

    fun release() {
        synchronized(lock) {
            holders--
            if (holders <= 0) {
                holders = 0
                runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
                multicastLock = null
            }
        }
    }

    /** Exposed so the diagnostics screen can prove the lock is not leaking. */
    val isHeld: Boolean get() = synchronized(lock) { holders > 0 }

    companion object {
        private const val TAG = "netscope-discovery"
    }
}
