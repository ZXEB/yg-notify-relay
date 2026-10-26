package dev.yg.relay.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import dev.yg.relay.model.DeviceInfo
import dev.yg.relay.model.EventIds

/**
 * 设备身份。
 *
 * 用 `ANDROID_ID` 派生而不是 IMEI/序列号：后者从 Android 10 起普通应用拿不到，
 * 而 ANDROID_ID 在同设备同签名下稳定、且不需要任何权限。哈希一层是为了
 * 让服务器上存的不是原始系统标识。
 */
object DeviceIdentity {

    private const val PREFS = "yg_relay_device"
    private const val KEY_ID = "device_id"

    fun of(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val id = "and_" + EventIds.digest(
            listOf(androidId, context.packageName, Build.MODEL, Build.MANUFACTURER)
        ).take(16)
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }
}

/** 每次上传时顺带带上的设备状态（心跳也用它）。 */
object DeviceSnapshot {

    fun of(context: Context, settings: SettingsStore): DeviceInfo {
        val battery = readBattery(context)
        return DeviceInfo(
            id = DeviceIdentity.of(context),
            alias = settings.alias.ifBlank { Build.MODEL ?: "Android" },
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            android = Build.VERSION.RELEASE ?: "",
            appVersion = appVersion(context),
            battery = battery.first,
            charging = battery.second,
            network = readNetwork(context),
        )
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun readBattery(context: Context): Pair<Int?, Boolean?> = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        percent to charging
    }.getOrElse { null to null }

    private fun readNetwork(context: Context): String? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }.getOrNull()
}
