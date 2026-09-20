package com.netscope.data

import android.content.Context
import com.netscope.core.model.OuiLookup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the offline OUI table.
 *
 * The table ships as an asset and is looked up entirely on device: a vendor lookup must
 * never become a network request that tells a third party what hardware is on the
 * user's LAN. It is a curated subset rather than the full IEEE registry, so an unknown
 * prefix reports NOT DISCOVERED instead of inventing a manufacturer.
 */
@Singleton
class OuiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mutex = Mutex()
    private var cached: OuiLookup? = null

    suspend fun lookup(): OuiLookup = mutex.withLock {
        cached ?: load().also { cached = it }
    }

    private suspend fun load(): OuiLookup = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().useLines { OuiLookup.parse(it) }
        }.getOrElse { OuiLookup(emptyMap()) }
    }

    private companion object {
        const val ASSET_NAME = "oui.txt"
    }
}
