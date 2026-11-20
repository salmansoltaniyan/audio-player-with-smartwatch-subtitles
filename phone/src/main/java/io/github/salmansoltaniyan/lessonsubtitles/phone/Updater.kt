package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The newest GitHub release. The workflow makes one release per build, tagged "build-<versionCode>". */
data class Release(
    val versionCode: Int,
    val title: String,
    val phoneApkUrl: String?,
    val watchApkUrl: String?,
)

/** Reads GitHub releases, downloads APKs and opens the Android installer for the phone APK. */
object Updater {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/salmansoltaniyan/audio-player-with-smartwatch-subtitles/releases/latest"
    private const val TAG_PREFIX = "build-"
    private const val PHONE_ASSET = "lesson-subtitles-phone.apk"
    private const val WATCH_ASSET = "lesson-subtitles-watch.apk"

    /** The latest release, or null if there is none yet. */
    suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val conn = open(LATEST_RELEASE_URL)
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null
                else -> throw IOException("GitHub answered ${conn.responseCode}")
            }
            val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val versionCode = release.getString("tag_name").removePrefix(TAG_PREFIX).toIntOrNull()
                ?: return@withContext null
            val assets = release.getJSONArray("assets")
            val urls = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .associate { it.getString("name") to it.getString("browser_download_url") }
            Release(
                versionCode = versionCode,
                title = release.optString("name", "build $versionCode"),
                phoneApkUrl = urls[PHONE_ASSET],
                watchApkUrl = urls[WATCH_ASSET],
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [url] into the app's cache as [fileName]. [onProgress] gets 0..100, or -1 if unknown. */
    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, fileName)
        file.delete()

        val conn = open(url) // GitHub redirects to its file server; followed automatically
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -2
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** Opens the system installer for [apk]. Android asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "LessonSubtitles-Android")
        }
}
