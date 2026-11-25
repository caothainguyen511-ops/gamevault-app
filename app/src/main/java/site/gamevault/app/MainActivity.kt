package site.gamevault.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var progress: ProgressBar? = null
    private var statusText: TextView? = null
    private var detailText: TextView? = null
    @Volatile private var cancelDownload = false
    private val site = "https://congdonggialap.site/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16,16,24)
        window.navigationBarColor = Color.rgb(16,16,24)
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(16,16,24))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(DownloadBridge(), "GameVaultNative")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript("""
                    (function() {
                      if (window.__gvNativeDownloadHook) return;
                      window.__gvNativeDownloadHook = true;
                      const originalOpen = window.open;
                      window.open = function(url) {
                        if (url && /^https?:/i.test(String(url))) {
                          GameVaultNative.downloadFile(String(url), document.title || 'GameVault');
                          return null;
                        }
                        return originalOpen ? originalOpen.apply(this, arguments) : null;
                      };
                    })();
                """.trimIndent(), null)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host ?: return false
                if (host == "congdonggialap.site" || host.endsWith(".congdonggialap.site")) return false
                if (uri.scheme == "https" || uri.scheme == "http") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }
        web.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            startDownload(url, userAgent ?: web.settings.userAgentString, contentDisposition ?: "", filename)
        })
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl(site) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun downloadFile(url: String, pageTitle: String) {
            if (!url.startsWith("https://")) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Chỉ tải liên kết HTTPS", Toast.LENGTH_SHORT).show() }
                return
            }
            val filename = URLUtil.guessFileName(url, null, null)
            runOnUiThread { startDownload(url, web.settings.userAgentString, "", filename) }
        }
    }

    private fun startDownload(url: String, agent: String, disposition: String, filename: String) {
        cancelDownload = false
        showDownloadDialog(filename)
        executor.execute {
            var connection: HttpURLConnection? = null
            var output: java.io.OutputStream? = null
            var input: java.io.InputStream? = null
            var rowUri: Uri? = null
            var file: File? = null
            val start = System.currentTimeMillis()
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", agent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Referer", web.url ?: site)
                    CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
                    connect()
                }
                val code = connection.responseCode
                if (code !in 200..299) throw Exception("HTTP $code")
                val total = connection.contentLengthLong
                val safeName = cleanName(filename.ifBlank { "GameVault-download.bin" })
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, connection.contentType?.substringBefore(";") ?: "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GameVault")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    rowUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Không tạo được file tải")
                    output = contentResolver.openOutputStream(rowUri!!) ?: throw Exception("Không mở được file")
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GameVault")
                    if (!dir.exists() && !dir.mkdirs()) throw Exception("Không tạo được thư mục")
                    file = File(dir, safeName)
                    output = FileOutputStream(file)
                }
                input = connection.inputStream
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastUpdate = 0L
                while (true) {
                    if (cancelDownload) throw InterruptedException("Đã hủy")
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 300) {
                        lastUpdate = now
                        val elapsed = max(1L, now - start) / 1000.0
                        val speed = downloaded / elapsed
                        val eta = if (total > 0 && speed > 0) ((total - downloaded) / speed).toLong() else -1L
                        val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        handler.post {
                            progress?.isIndeterminate = total <= 0
                            if (total > 0) progress?.progress = pct
                            statusText?.text = if (total > 0) "$pct% • ${bytes(downloaded)} / ${bytes(total)}" else "${bytes(downloaded)} đã tải"
                            detailText?.text = "Đã tải ${duration(elapsed.toLong())} • ${bytes(speed.toLong())}/s • Còn ${if (eta >= 0) duration(eta) else "đang tính"}"
                        }
                    }
                }
                output.flush()
                if (Build.VERSION.SDK_INT >= 29 && rowUri != null) {
                    val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    contentResolver.update(rowUri!!, values, null, null)
                }
                handler.post {
                    dialog?.dismiss()
                    dialog = null
                    Toast.makeText(this, "Đã tải xong: Download/GameVault/$safeName", Toast.LENGTH_LONG).show()
                }
            } catch (e: InterruptedException) {
                if (rowUri != null) try { contentResolver.delete(rowUri!!, null, null) } catch (_: Exception) {}
                file?.delete()
                handler.post { dialog?.dismiss(); dialog = null; Toast.makeText(this, "Đã hủy tải", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                if (rowUri != null) try { contentResolver.delete(rowUri!!, null, null) } catch (_: Exception) {}
                file?.delete()
                handler.post { dialog?.dismiss(); dialog = null; Toast.makeText(this, "Tải lỗi: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { output?.close() } catch (_: Exception) {}
                connection?.disconnect()
            }
        }
    }

    private fun showDownloadDialog(filename: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val title = TextView(this).apply {
            text = "Đang tải: $filename"
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        statusText = TextView(this).apply { text = "Đang kết nối…"; setTextColor(Color.DKGRAY); setPadding(0, 18, 0, 0) }
        detailText = TextView(this).apply { text = "Đang tính thời gian…"; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 0) }
        box.addView(title)
        box.addView(progress, LinearLayout.LayoutParams(-1, 12).apply { topMargin = 24 })
        box.addView(statusText)
        box.addView(detailText)
        dialog = AlertDialog.Builder(this)
            .setTitle("GameVault Downloader")
            .setView(box)
            .setNegativeButton("Hủy") { _, _ -> cancelDownload = true }
            .setOnCancelListener { cancelDownload = true }
            .create()
        dialog?.show()
    }

    private fun cleanName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(160)
        return cleaned.ifBlank { "GameVault-download.bin" }
    }
    private fun bytes(n: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return DecimalFormat("0.##").format(v) + " " + units[i]
    }
    private fun duration(s: Long): String {
        val sec = s.coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }
}
