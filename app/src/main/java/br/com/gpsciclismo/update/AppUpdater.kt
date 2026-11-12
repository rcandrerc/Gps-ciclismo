package br.com.gpsciclismo.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import br.com.gpsciclismo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class RemoteVersion(val versionCode: Int, val versionName: String, val notes: String = "")

/**
 * Atualização pelo próprio app: confere o version.json da release "ultima-versao" no GitHub,
 * baixa o APK novo e abre o instalador do Android (o usuário confirma "Instalar").
 */
class AppUpdater(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** Retorna a versão remota se ela for mais nova que a instalada; null se não há ou se falhou. */
    suspend fun check(): RemoteVersion? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open("${BuildConfig.UPDATE_BASE_URL}/version.json?t=${System.currentTimeMillis()}")
            try {
                if (conn.responseCode != 200) return@runCatching null
                val remote = json.decodeFromString<RemoteVersion>(conn.inputStream.bufferedReader().readText())
                remote.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Baixa o APK informando o progresso (0..1). */
    suspend fun download(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "gps-ciclismo.apk")
        val conn = open("${BuildConfig.UPDATE_BASE_URL}/gps-ciclismo.apk")
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** O Android exige liberar "instalar apps desconhecidos" para este app uma única vez. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
