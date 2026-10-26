package com.mojahid.watchcompanion

import android.content.Context

/**
 * One app-scoped BLE session. Compose recreations and page navigation do not
 * tear down the Watch 7 connection. The Android OS still may stop our process;
 * when the user opens Momo again, an opted-in device is reconnected.
 *
 * No Android firmware or proprietary watch service is modified.
 */
object DirectWatchSession {
    @Volatile
    private var instance: DirectWatchClient? = null

    fun get(context: Context): DirectWatchClient = synchronized(this) {
        instance ?: DirectWatchClient(context.applicationContext).also {
            instance = it
        }
    }
}
