package com.arrestme.benchmark.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException

class OfflineIoException(
    message: String = "No active network connection available."
) : IOException(message)

interface NetworkConnectivityChecker {
    fun isOnline(): Boolean
}

class AndroidNetworkConnectivityChecker(
    context: Context
) : NetworkConnectivityChecker {
    private val appContext = context.applicationContext

    override fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class AlwaysOnlineConnectivityChecker : NetworkConnectivityChecker {
    override fun isOnline(): Boolean = true
}
