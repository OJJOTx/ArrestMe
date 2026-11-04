package com.arrestme.benchmark.core.backend

import android.os.Build
import com.arrestme.benchmark.BuildConfig

data class LocalDeviceMetadata(
    val deviceName: String,
    val manufacturer: String?,
    val model: String?,
    val appVersion: String,
    val androidVersion: String,
    val sdkInt: Int
)

interface DeviceMetadataProvider {
    fun currentMetadata(): LocalDeviceMetadata
}

/**
 * Provides non-invasive Android OS/build metadata for cloud device registration.
 * Deliberately avoids collecting any hardware identifiers (no IMEI, serial, MAC, or ANDROID_ID).
 */
class AndroidDeviceMetadataProvider : DeviceMetadataProvider {
    override fun currentMetadata(): LocalDeviceMetadata {
        val rawManufacturer = Build.MANUFACTURER?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
        val rawModel = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }?.take(64)

        val friendlyName = when {
            rawManufacturer != null && rawModel != null -> {
                if (rawModel.startsWith(rawManufacturer, ignoreCase = true)) {
                    rawModel
                } else {
                    "${rawManufacturer.replaceFirstChar { it.uppercase() }} $rawModel"
                }
            }
            rawModel != null -> rawModel
            rawManufacturer != null -> rawManufacturer
            else -> "Android Device"
        }.trim().take(100).ifEmpty { "Android Device" }

        val versionName = BuildConfig.VERSION_NAME.trim().take(48).ifEmpty { "0.3.0" }
        val androidRelease = (Build.VERSION.RELEASE ?: "Unknown").trim().take(32).ifEmpty { "Unknown" }
        val sdkInt = Build.VERSION.SDK_INT.coerceIn(26, 60)

        return LocalDeviceMetadata(
            deviceName = friendlyName,
            manufacturer = rawManufacturer,
            model = rawModel,
            appVersion = versionName,
            androidVersion = androidRelease,
            sdkInt = sdkInt
        )
    }
}
