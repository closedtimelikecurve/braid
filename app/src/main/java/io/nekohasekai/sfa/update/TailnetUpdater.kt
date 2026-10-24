package io.nekohasekai.sfa.update

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过自建清单拉取更新（设计上放在 tailnet 内的家用服务器/开发机上）。
 *
 * 为什么不用 GitHub：这个分支是自维护的，上游版本号对不上，也不该把用户引去装官方 SFA。
 * 清单地址存在「应用设置 → 更新设置 → 自建更新源」里，**默认为空**：
 * 公开发布的 APK 不内置任何人的服务器地址 —— 没配 URL 就跳过自建源检查，
 * 静默退回上游渠道。想用的人（比如开发者自己）在设置里填上完整清单 URL 即可：
 * ```
 * http://<你的机器>:8088/weave/version.json
 * ```
 * 清单是 [UpdateInfo] 的 JSON：
 * ```
 * { "versionCode": 7459666, "versionName": "1.15.0-alpha.12-ts",
 *   "downloadUrl": "http://.../weave-latest.apk",
 *   "releaseUrl": "...", "releaseNotes": "...", "isPrerelease": false, "fileSize": 66663997 }
 * ```
 *
 * APK 的完整性不靠清单里的哈希 —— 安装时 Android 会校验签名，
 * 拿不到同一个签名私钥就装不上，这比自签哈希可靠。
 * 明文 HTTP 需要在 `network_security_config.xml` 里为你的主机放行（ts.net 已放行）。
 */
object TailnetUpdater {

    private const val TAG = "TailnetUpdater"

    /** 未配置自建源时的占位，仅用于常量命名兼容；实际 URL 一律读 Settings */
    internal const val PORT = 8088

    fun checkForUpdate(): UpdateInfo? {
        val manifestUrl = runCatching { Settings.tailnetUpdateUrl.trim() }.getOrNull().orEmpty()
        if (manifestUrl.isBlank()) return null
        return checkManifest(manifestUrl)
    }

    private fun checkManifest(manifestUrl: String): UpdateInfo? {
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "manifest unavailable: HTTP ${connection.responseCode}")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val info = UpdateInfo.fromJson(body)
            if (info == null) Log.w(TAG, "manifest parse failed: $body")
            return info
        } catch (e: Exception) {
            Log.w(TAG, "manifest fetch failed: $e")
            return null
        } finally {
            connection.disconnect()
        }
    }
}
