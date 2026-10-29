package com.orkeit.browservpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASE_API = "https://api.github.com/repos/mohmedelmotaz151-ctrl/Oriket-browser-vpn/releases/latest"
private const val UPDATE_TAG = "OriketUpdate"

internal fun checkForOriketUpdate(activity: Activity, report: (String) -> Unit) {
    report("جارٍ البحث عن تحديثات Oriket Browser VPN…")
    Thread {
        try {
            val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Oriket-Browser-VPN")
            }
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            connection.disconnect()

            val latestTag = release.optString("tag_name").removePrefix("v").trim()
            val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
            val assets = release.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (latestTag.isBlank() || compareVersions(latestTag, currentVersion) <= 0) {
                    report("أنت تستخدم أحدث إصدار ($currentVersion).")
                    return@runOnUiThread
                }
                val downloadUrl = apkUrl
                if (downloadUrl.isNullOrBlank()) {
                    report("يوجد إصدار $latestTag، لكن ملف التثبيت غير مرفق بالإصدار بعد.")
                    return@runOnUiThread
                }
                report("يتوفر تحديث $latestTag؛ جارٍ تنزيله…")
                downloadAndInstall(activity, downloadUrl, latestTag, report)
            }
        } catch (error: Exception) {
            Log.e(UPDATE_TAG, "Update check failed", error)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    report("تعذر البحث عن التحديث. تحقق من اتصال الإنترنت وحاول مجددًا.")
                }
            }
        }
    }.start()
}

private fun downloadAndInstall(activity: Activity, downloadUrl: String, version: String, report: (String) -> Unit) {
    Thread {
        try {
            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Oriket-Browser-VPN")
            }
            val updateDir = File(activity.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "Oriket-Browser-VPN-$version.apk")
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (apkFile.length() < 100_000L) throw IllegalStateException("Downloaded APK is unexpectedly small")

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                    report("تم تنزيل التحديث. اسمح للتطبيق بتثبيت التحديثات من هذا المصدر، ثم اضغط زر التحديث مرة أخرى.")
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    activity.startActivity(intent)
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                report("اكتمل تنزيل التحديث؛ افتح شاشة التثبيت واضغط تثبيت.")
                activity.startActivity(installIntent)
            }
        } catch (error: Exception) {
            Log.e(UPDATE_TAG, "Update download/install failed", error)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    report("فشل تنزيل التحديث أو فتح المثبّت. حاول مرة أخرى.")
                }
            }
        }
    }.start()
}

private fun compareVersions(left: String, right: String): Int {
    val a = left.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
    val b = right.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}
