package br.com.negocia.trader

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class UpdateHelper(private val activity: Activity) {
    private val releaseApi = "https://api.github.com/repos/gestarlink/negocia-trader/releases/latest"
    fun check(onUpdate: (String, () -> Unit) -> Unit, manual: Boolean = false) {
        thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(releaseApi).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "NegocIA-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (connection.responseCode != 200) {
                    if (manual) show("Nenhuma atualização pública disponível.")
                    return@thread
                }
                val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val version = data.optString("tag_name").removePrefix("v")
                val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.1.0"
                if (compare(version, current) <= 0) {
                    if (manual) show("Você já está na versão mais recente.")
                    return@thread
                }
                val assets = data.optJSONArray("assets")
                var apk = ""
                if (assets != null) for (i in 0 until assets.length()) {
                    val item = assets.getJSONObject(i)
                    if (item.optString("name").endsWith(".apk")) {
                        apk = item.optString("browser_download_url")
                        break
                    }
                }
                if (apk.isBlank()) {
                    if (manual) show("Nova versão ainda sem APK.")
                    return@thread
                }
                val link = apk
                activity.runOnUiThread { onUpdate(version) { download(link) } }
            } catch (_: Exception) {
                if (manual) show("Falha ao verificar atualização.")
            } finally { connection?.disconnect() }
        }
    }
    private fun compare(a: String, b: String): Int {
        val aa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val c = (aa.getOrNull(i) ?: 0).compareTo(bb.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return 0
    }
    private fun show(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }
    private fun download(link: String) {
        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(link))
                .setTitle("Atualização do NIA")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "nia-update.apk")
            manager.enqueue(request)
            show("Download iniciado. Abra o APK baixado para confirmar a instalação.")
        } catch (_: Exception) { show("Não foi possível iniciar o download.") }
    }
}
