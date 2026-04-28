// ═══════════════════════════════════════════════════════════════════
// Grey Browser - V2.0 (Extension Support + Cleaned Features)
// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package, Imports, MainActivity, DownloadService ===
// ═══════════════════════════════════════════════════════════════════

package com.grey.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebResponse
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setContent { GreyBrowser() }
    }
    override fun onPause() { super.onPause(); saveTabs(this) }
    override fun onDestroy() { super.onDestroy(); saveTabs(this) }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "grey_downloads",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

class DownloadService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "grey_downloads")
            .setContentTitle("Grey is downloading")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

// END OF PART 1/10
// ═══════════════════════════════════════════════════════════════════
// === PART 2/10 — FaviconCache, Constants, SPEED_LIMITS ===
// ═══════════════════════════════════════════════════════════════════

object FaviconCache {
    private const val MAX_FAVICONS = 50
    private const val FAVICON_DIR = "favicons"
    private const val META_FILE = "favicon_meta.json"
    data class FaviconMeta(val domain: String, val lastAccessed: Long = System.currentTimeMillis())
    
    private fun getFaviconDir(context: Context): File {
        val dir = File(context.filesDir, FAVICON_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    private fun getMetaFile(context: Context) = File(context.filesDir, META_FILE)
    
    private fun loadMeta(context: Context): MutableList<FaviconMeta> {
        val file = getMetaFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val json = file.readText()
            val array = JSONArray(json)
            mutableListOf<FaviconMeta>().apply {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(FaviconMeta(obj.getString("domain"), obj.getLong("lastAccessed")))
                }
            }
        } catch (e: Exception) { mutableListOf() }
    }
    
    private fun saveMeta(context: Context, meta: List<FaviconMeta>) {
        val array = JSONArray()
        for (item in meta) {
            val obj = JSONObject()
            obj.put("domain", item.domain)
            obj.put("lastAccessed", item.lastAccessed)
            array.put(obj)
        }
        getMetaFile(context).writeText(array.toString())
    }
    
    fun getFaviconFile(context: Context, domain: String) =
        File(getFaviconDir(context), domain.replace(".", "_").replace("/", "_") + ".png")
    
    fun getFaviconBitmap(context: Context, domain: String): Bitmap? {
        val file = getFaviconFile(context, domain)
        if (!file.exists()) return null
        val meta = loadMeta(context)
        val existing = meta.find { it.domain == domain }
        if (existing != null) {
            meta.remove(existing)
            meta.add(existing.copy(lastAccessed = System.currentTimeMillis()))
        } else {
            meta.add(FaviconMeta(domain, System.currentTimeMillis()))
        }
        saveMeta(context, meta)
        return BitmapFactory.decodeFile(file.absolutePath)
    }
    
    private fun tryDownload(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 4000; conn.connect()
            if (conn.responseCode == 200) {
                val b = BitmapFactory.decodeStream(conn.inputStream)
                conn.inputStream.close(); conn.disconnect()
                b
            } else { conn.disconnect(); null }
        } catch (e: Exception) { null }
    }
    
    suspend fun downloadAndCacheFavicon(context: Context, domain: String): Bitmap? = withContext(Dispatchers.IO) {
        val sources = listOf(
            "https://www.google.com/s2/favicons?domain=$domain&sz=64",
            "https://$domain/favicon.ico",
            "https://$domain/favicon.png"
        )
        var bitmap: Bitmap? = null
        for (src in sources) { bitmap = tryDownload(src); if (bitmap != null) break }
        if (bitmap != null) {
            FileOutputStream(getFaviconFile(context, domain)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val meta = loadMeta(context)
            meta.removeAll { it.domain == domain }
            meta.add(FaviconMeta(domain, System.currentTimeMillis()))
            if (meta.size > MAX_FAVICONS) {
                val oldest = meta.minByOrNull { it.lastAccessed }
                if (oldest != null) { meta.remove(oldest); getFaviconFile(context, oldest.domain).delete() }
            }
            saveMeta(context, meta)
        }
        bitmap
    }
}

private const val PREFS_NAME = "browser_tabs"
private const val KEY_TABS = "saved_tabs"
private const val KEY_PINNED = "pinned_domains"
private const val KEY_LAST_ACTIVE_URL = "last_active_url"
private const val KEY_HISTORY = "saved_history"
private const val KEY_BOOKMARKS = "saved_bookmarks"
private const val KEY_EXTENSIONS = "saved_extensions"

const val MAX_LOADED_TABS = 10
const val MAX_WARM_TABS = 9
const val UNDO_DELAY_MS = 3000L
const val MAX_HISTORY_ITEMS = 500

val SPEED_LIMITS = listOf(0L, 50L * 1024, 100L * 1024, 250L * 1024, 500L * 1024, 1024L * 1024)
fun formatSpeedLimit(limit: Long): String = when (limit) {
    0L -> "No Limit"
    50L * 1024 -> "50 KB/s"
    100L * 1024 -> "100 KB/s"
    250L * 1024 -> "250 KB/s"
    500L * 1024 -> "500 KB/s"
    1024L * 1024 -> "1 MB/s"
    else -> "No Limit"
}

// END OF PART 2/10
// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Data Classes, Enums, Save/Load Functions ===
// ═══════════════════════════════════════════════════════════════════

data class HistoryItem(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val folder: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class ExtensionItem(
    val id: String,
    val name: String,
    val description: String = "",
    val iconUrl: String = "",
    val enabled: Boolean = true,
    val source: String = "store"
)

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val fileSize: Long = 0L,
    var downloadedBytes: Long = 0L,
    var state: DownloadState = DownloadState.QUEUED,
    var progress: Int = 0,
    var speed: String = "",
    val referer: String = "",
    val cookies: String = "",
    var isVideo: Boolean = false,
    var tempFile: File? = null
)

class TabState {
    var session by mutableStateOf<GeckoSession?>(null)
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
    var isBlankTab by mutableStateOf(true)
    var isDiscarded by mutableStateOf(false)
}

fun saveTabs(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply()
}

fun saveTabsData(context: Context, tabs: List<TabState>, pinnedDomains: List<String>, lastActiveUrl: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsArray = JSONArray()
    for (tab in tabs) {
        if (!tab.isBlankTab) {
            val obj = JSONObject()
            obj.put("url", tab.url)
            obj.put("title", tab.title)
            tabsArray.put(obj)
        }
    }
    prefs.edit().putString(KEY_TABS, tabsArray.toString()).putString(KEY_LAST_ACTIVE_URL, lastActiveUrl).apply()
    val pinnedArray = JSONArray()
    for (d in pinnedDomains) pinnedArray.put(d)
    prefs.edit().putString(KEY_PINNED, pinnedArray.toString()).apply()
}

fun loadTabsData(context: Context): Triple<List<Pair<String, String>>, List<String>, String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val tabsList = mutableListOf<Pair<String, String>>()
    prefs.getString(KEY_TABS, null)?.let { json ->
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tabsList.add(Pair(o.getString("url"), o.optString("title", o.getString("url"))))
            }
        } catch (e: Exception) { }
    }
    val pinnedList = mutableListOf<String>()
    prefs.getString(KEY_PINNED, null)?.let { json ->
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) pinnedList.add(arr.getString(i))
        } catch (e: Exception) { }
    }
    val lastActiveUrl = prefs.getString(KEY_LAST_ACTIVE_URL, "") ?: ""
    return Triple(tabsList, pinnedList, lastActiveUrl)
}

fun saveHistory(context: Context, history: List<HistoryItem>) {
    val arr = JSONArray()
    for (h in history) {
        val obj = JSONObject()
        obj.put("url", h.url); obj.put("title", h.title); obj.put("timestamp", h.timestamp)
        arr.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply()
}

fun loadHistory(context: Context): List<HistoryItem> {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HISTORY, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        mutableListOf<HistoryItem>().apply {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(HistoryItem(o.getString("url"), o.getString("title"), o.getLong("timestamp")))
            }
        }
    } catch (e: Exception) { emptyList() }
}

fun saveBookmarks(context: Context, bookmarks: List<Bookmark>) {
    val arr = JSONArray()
    for (b in bookmarks) {
        val obj = JSONObject()
        obj.put("id", b.id); obj.put("url", b.url)
        obj.put("title", b.title); obj.put("folder", b.folder)
        obj.put("timestamp", b.timestamp)
        arr.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
}

fun loadBookmarks(context: Context): List<Bookmark> {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_BOOKMARKS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        mutableListOf<Bookmark>().apply {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(Bookmark(o.getString("id"), o.getString("url"), o.getString("title"),
                    o.optString("folder", ""), o.getLong("timestamp")))
            }
        }
    } catch (e: Exception) { emptyList() }
}

fun saveExtensions(context: Context, extensions: List<ExtensionItem>) {
    val arr = JSONArray()
    for (e in extensions) {
        val obj = JSONObject()
        obj.put("id", e.id); obj.put("name", e.name)
        obj.put("description", e.description); obj.put("iconUrl", e.iconUrl)
        obj.put("enabled", e.enabled); obj.put("source", e.source)
        arr.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_EXTENSIONS, arr.toString()).apply()
}

fun loadExtensions(context: Context): List<ExtensionItem> {
    val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_EXTENSIONS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        mutableListOf<ExtensionItem>().apply {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(ExtensionItem(o.getString("id"), o.getString("name"),
                    o.optString("description", ""), o.optString("iconUrl", ""),
                    o.getBoolean("enabled"), o.optString("source", "store")))
            }
        }
    } catch (e: Exception) { emptyList() }
}

// END OF PART 3/10
// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — Utility Functions, NetworkSpeedLimiter, ExtensionManager ===
// ═══════════════════════════════════════════════════════════════════

fun getDomainName(url: String): String {
    if (url == "about:blank" || url.isBlank()) return ""
    return try {
        val host = Uri.parse(url).host?.removePrefix("www.") ?: return ""
        val parts = host.split(".")
        if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    } catch (e: Exception) { "Unknown" }
}

fun resolveUrl(input: String): String {
    if (input.isBlank()) return "about:blank"
    if (input.contains("://") || (input.contains(".") && !input.contains(" "))) {
        return if (input.contains("://")) input else "https://$input"
    }
    return "https://www.google.com/search?q=${Uri.encode(input)}"
}

// ── Global Network Speed Limiter (per-second window) ──────────────
class NetworkSpeedLimiter {
    @Volatile var maxBytesPerSecond: Long = 0L
    
    private var bytesThisSecond: Long = 0L
    private var windowStartNs: Long = System.nanoTime()
    private val lock = Any()
    
    fun acquire(bytes: Long) {
        val limit = maxBytesPerSecond
        if (limit <= 0L) return
        
        synchronized(lock) {
            while (true) {
                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - windowStartNs
                
                if (elapsedNs >= 1_000_000_000L) {
                    bytesThisSecond = 0L
                    windowStartNs = nowNs
                }
                
                if (bytesThisSecond + bytes <= limit) {
                    bytesThisSecond += bytes
                    return
                }
                
                val remainingNs = 1_000_000_000L - elapsedNs
                if (remainingNs > 0) {
                    val waitMs = remainingNs / 1_000_000L
                    val waitNs = (remainingNs % 1_000_000L).toInt()
                    if (waitMs > 0) {
                        try { (lock as java.lang.Object).wait(waitMs, waitNs) }
                        catch (e: InterruptedException) { return }
                    } else {
                        try { (lock as java.lang.Object).wait(1) }
                        catch (e: InterruptedException) { return }
                    }
                }
            }
        }
    }
}

// ── Extension Manager ──────────────────────────────────────────────
class ExtensionManager(private val runtime: GeckoRuntime) {
    
    fun installFromUrl(url: String, onResult: (Boolean, String) -> Unit) {
        thread(name = "ext-install") {
            try {
                val xpiUrl = URL(url)
                val conn = xpiUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 30000; conn.readTimeout = 30000
                conn.connect()
                
                if (conn.responseCode != 200) {
                    onResult(false, "Failed to download: HTTP ${conn.responseCode}")
                    return@thread
                }
                
                val xpiFile = File.createTempFile("extension", ".xpi")
                FileOutputStream(xpiFile).use { output ->
                    conn.inputStream.use { input -> input.copyTo(output) }
                }
                conn.disconnect()
                
                val controller = runtime.webExtensionController
                val uri = Uri.fromFile(xpiFile)
                controller.install(uri).accept({ ext ->
                    onResult(true, ext?.id ?: "installed")
                }, { error ->
                    onResult(false, "Install failed: ${error.message}")
                })
            } catch (e: Exception) {
                onResult(false, "Error: ${e.message}")
            }
        }
    }
    
    fun uninstall(extId: String) {
        runtime.webExtensionController.uninstall(extId)
    }
    
    fun setEnabled(extId: String, enabled: Boolean) {
        if (enabled) {
            runtime.webExtensionController.enable(extId)
        } else {
            runtime.webExtensionController.disable(extId)
        }
    }
    
    fun searchStore(query: String, onResult: (List<ExtensionItem>) -> Unit) {
        thread(name = "ext-search") {
            try {
                val apiUrl = "https://addons.mozilla.org/api/v5/addons/search/?q=${Uri.encode(query)}&type=extension&app=android&sort=rating&page_size=20"
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.connect()
                
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                
                val results = mutableListOf<ExtensionItem>()
                val root = JSONObject(json)
                val resultsArray = root.optJSONArray("results")
                if (resultsArray != null) {
                    for (i in 0 until resultsArray.length()) {
                        val ext = resultsArray.getJSONObject(i)
                        results.add(ExtensionItem(
                            id = ext.optString("guid", ""),
                            name = ext.optJSONObject("name")?.optString("en-US", "") ?: "",
                            description = ext.optJSONObject("summary")?.optString("en-US", "") ?: "",
                            iconUrl = ext.optString("icon_url", ""),
                            enabled = false,
                            source = "store"
                        ))
                    }
                }
                onResult(results)
            } catch (e: Exception) {
                onResult(emptyList())
            }
        }
    }
    
    fun getFeatured(onResult: (List<ExtensionItem>) -> Unit) {
        searchStore("recommended", onResult)
    }
}

// END OF PART 4/10
// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 — GreyBrowser() State Declarations ===
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val activity = context as? ComponentActivity
    val focusManager = LocalFocusManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember {
        context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    // ── Extension State ───────────────────────────────────────────────
    val extensionManager = remember { ExtensionManager(runtime) }
    val extensions = remember {
        mutableStateListOf<ExtensionItem>().apply { addAll(loadExtensions(context)) }
    }
    var showExtensions by remember { mutableStateOf(false) }

    // ── Download State ────────────────────────────────────────────────
    val activeDownloads = remember { mutableStateListOf<DownloadItem>() }
    var currentDownloadId by remember { mutableStateOf<String?>(null) }
    var showDownloadManager by remember { mutableStateOf(false) }
    var downloadUpdateTrigger by remember { mutableIntStateOf(0) }
    // Detected videos (from onExternalResponse + onLocationChange + onContextMenu)
    val detectedVideos = remember { mutableStateListOf<Pair<String, String>>() }
    var showVideoDropdown by remember { mutableStateOf(false) }
    // Download Editor state
    var showDownloadEditor by remember { mutableStateOf(false) }
    var downloadEditorUrl by remember { mutableStateOf("") }
    var downloadEditorName by remember { mutableStateOf("") }
    var downloadEditorSize by remember { mutableStateOf("Unknown") }
    var downloadEditorIsVideo by remember { mutableStateOf(false) }
    // Global network speed limiter
    val networkLimiter = remember { NetworkSpeedLimiter() }
    // ── History State ──────────────────────────────────────────────────
    val history = remember { mutableStateListOf<HistoryItem>().apply { addAll(loadHistory(context)) } }
    var showHistory by remember { mutableStateOf(false) }
    // ── Bookmark State ─────────────────────────────────────────────────
    val bookmarks = remember { mutableStateListOf<Bookmark>().apply { addAll(loadBookmarks(context)) } }
    var showBookmarks by remember { mutableStateOf(false) }
    // ── Toast State ────────────────────────────────────────────────────
    var toastMessage by remember { mutableStateOf("") }
    var showToast by remember { mutableStateOf(false) }

    val applySettingsToSession: (GeckoSession) -> Unit = { session ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = true
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }

    fun addToHistory(url: String, title: String) {
        if (url == "about:blank" || url.isBlank()) return
        history.removeAll { it.url == url }
        history.add(HistoryItem(url = url, title = title.ifBlank { url }, timestamp = System.currentTimeMillis()))
        if (history.size > MAX_HISTORY_ITEMS) {
            history.removeAt(0)
        }
        saveHistory(context, history)
    }

    fun showToast(msg: String) {
        toastMessage = msg
        showToast = true
    }

    val homeSession = remember {
        GeckoSession().apply {
            applySettingsToSession(this); open(runtime); loadUri("about:blank")
        }
    }
    val homeTab = remember {
        TabState().apply {
            session = homeSession; isBlankTab = true; title = "Home"
        }
    }

    val (savedTabs, savedPinned, savedLastActiveUrl) = remember { loadTabsData(context) }
    val tabs = remember {
        mutableStateListOf<TabState>().apply {
            for ((url, title) in savedTabs) {
                add(TabState().apply {
                    this.url = url; this.title = title; isBlankTab = false
                    isDiscarded = true; session = null
                })
            }
        }
    }

    var currentTabIndex by remember { mutableIntStateOf(-1) }
    var highlightedTabIndex by remember {
        mutableIntStateOf(
            tabs.indexOfFirst { it.url == savedLastActiveUrl }.let { if (it >= 0) it else -1 }
        )
    }
    var lastActiveUrl by remember { mutableStateOf(savedLastActiveUrl) }

    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val pinnedDomains = remember {
        mutableStateListOf<String>().apply { addAll(savedPinned) }
    }
    var selectedDomain by remember { mutableStateOf("") }
    val faviconBitmaps = remember { mutableStateMapOf<String, Bitmap?>() }
    val faviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val tabFavicons = remember { mutableStateMapOf<String, Bitmap?>() }
    val tabFaviconLoading = remember { mutableStateMapOf<String, Boolean>() }
    val currentTab = if (currentTabIndex == -1) homeTab else tabs.getOrNull(currentTabIndex) ?: homeTab

    val pendingDeletions = remember { mutableStateMapOf<Int, Long>() }
    var showBlink by remember { mutableStateOf(false) }
    val blinkTargetDomain = remember { mutableStateOf("") }

    LaunchedEffect(tabs.toList(), pinnedDomains.toList(), lastActiveUrl) {
        saveTabsData(context, tabs, pinnedDomains, lastActiveUrl)
    }
    LaunchedEffect(tabs.map { "${it.url}|${it.title}" }.joinToString()) {
        saveTabsData(context, tabs, pinnedDomains, lastActiveUrl)
    }
    LaunchedEffect(bookmarks.toList()) { saveBookmarks(context, bookmarks) }
    LaunchedEffect(extensions.toList()) { saveExtensions(context, extensions) }

    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
            lastActiveUrl = tabs[currentTabIndex].url
            highlightedTabIndex = currentTabIndex
        }
    }

    // LaunchedEffect for toast auto-hide
    if (showToast) {
        LaunchedEffect(showToast) {
            delay(2000)
            showToast = false
        }
    }

// END OF PART 5/10
// ═══════════════════════════════════════════════════════════════════
// === PART 6/10 — GreyBrowser() Delegates, Lifecycle, Tab Functions ===
// ═══════════════════════════════════════════════════════════════════

    val setupDelegates = fun(tabState: TabState) {
        val session = tabState.session ?: return
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                tabState.url = url; tabState.progress = 5
                tabState.lastUpdated = System.currentTimeMillis()
                if (url != "about:blank") {
                    tabState.isBlankTab = false
                    lastActiveUrl = url
                    if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
                        highlightedTabIndex = currentTabIndex
                    }
                    // Clear video dropdown on navigation
                    if (tabs.getOrNull(currentTabIndex) == tabState || (currentTabIndex == -1 && tabState == homeTab)) {
                        detectedVideos.clear()
                        showVideoDropdown = false
                    }
                }
            }
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                tabState.progress = 100; tabState.lastUpdated = System.currentTimeMillis()
                if (success && tabState.url != "about:blank") {
                    addToHistory(tabState.url, tabState.title)
                }
            }
            override fun onProgressChange(s: GeckoSession, progress: Int) {
                tabState.progress = progress
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                if (!tabState.isBlankTab) tabState.title = title ?: tabState.url
            }
            override fun onContextMenu(
                session: GeckoSession, screenX: Int, screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                if (element.linkUri != null) {
                    contextMenuUri = element.linkUri; showContextMenu = true
                    // Detect video links from context menu
                    val lowerUrl = element.linkUri!!.lowercase()
                    if (lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".webm") || 
                        lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".ts")) {
                        if (!detectedVideos.any { it.first == element.linkUri }) {
                            detectedVideos.add(Pair(element.linkUri!!, tabState.url))
                            showVideoDropdown = true
                        }
                    }
                }
            }
            override fun onExternalResponse(
                session: GeckoSession,
                response: WebResponse
            ) {
                val url = response.uri
                if (url != null) {
                    val fileName = url.substringAfterLast("/").substringBefore("?")
                    downloadEditorUrl = url
                    downloadEditorName = if (fileName.isNotBlank()) fileName else tabState.title
                    downloadEditorSize = "Unknown"
                    downloadEditorIsVideo = url.contains(".mp4") || url.contains(".webm") || url.contains(".m3u8")
                    showDownloadEditor = true
                    // Also add to detected videos
                    if (downloadEditorIsVideo && !detectedVideos.any { it.first == url }) {
                        detectedVideos.add(Pair(url, tabState.url))
                        showVideoDropdown = true
                    }
                }
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let { newUrl ->
                    tabState.url = newUrl
                    if (newUrl != "about:blank") {
                        tabState.isBlankTab = false

                        // ── Direct Video URL Detection ────────────
                        val lowerUrl = newUrl.lowercase()
                        if (lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".mp4") || 
                            lowerUrl.endsWith(".webm") || lowerUrl.endsWith(".ts")) {
                            downloadEditorUrl = newUrl
                            downloadEditorName = newUrl.substringAfterLast("/").substringBefore("?")
                            downloadEditorSize = "Unknown"
                            downloadEditorIsVideo = true
                            showDownloadEditor = true
                            if (!detectedVideos.any { it.first == newUrl }) {
                                detectedVideos.add(Pair(newUrl, tabState.url))
                                showVideoDropdown = true
                            }
                        }
                    }
                }
            }
        }
    }

    homeTab.session?.let { setupDelegates(homeTab) }

    fun manageTabLifecycle(activeIndex: Int) {
        tabs.forEachIndexed { i, ts ->
            if (i == activeIndex && ts.session == null && ts.isDiscarded) {
                val s = GeckoSession().apply {
                    applySettingsToSession(this); open(runtime); loadUri(ts.url)
                }
                ts.session = s; ts.isDiscarded = false; setupDelegates(ts)
                ts.lastUpdated = System.currentTimeMillis()
            }
        }
        tabs.forEachIndexed { i, ts -> ts.session?.setActive(i == activeIndex) }
        val warmTabs = tabs.filter { 
            val idx = tabs.indexOf(it)
            !it.isDiscarded && !it.isBlankTab && it.session != null && idx != activeIndex 
        }
        if (warmTabs.size > MAX_WARM_TABS) {
            val toMakeCold = warmTabs.sortedBy { it.lastUpdated }.take(warmTabs.size - MAX_WARM_TABS)
            for (tab in toMakeCold) {
                tab.session?.setActive(false); tab.session?.close()
                tab.session = null; tab.isDiscarded = true; tab.progress = 100
            }
        }
    }

    fun restoreTab(tab: TabState) {
        if (tab.isDiscarded && tab.session == null) {
            val s = GeckoSession().apply {
                applySettingsToSession(this); open(runtime); setActive(false); loadUri(tab.url)
            }
            tab.session = s; tab.isDiscarded = false; setupDelegates(tab)
            tab.lastUpdated = System.currentTimeMillis()
        }
    }

    fun startDownloadService(ctx: Context) {
        val intent = Intent(ctx, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopDownloadService(ctx: Context) {
        if (activeDownloads.none { it.state == DownloadState.DOWNLOADING }) {
            ctx.stopService(Intent(ctx, DownloadService::class.java))
        }
    }

    LaunchedEffect(pendingDeletions.toMap()) {
        while (pendingDeletions.isNotEmpty()) {
            delay(1000)
            val now = System.currentTimeMillis()
            val toRemove = pendingDeletions.filter { now - it.value >= UNDO_DELAY_MS }.keys.toList()
            for (index in toRemove.sortedDescending()) {
                pendingDeletions.remove(index)
                val tab = tabs.getOrNull(index)
                if (tab == null) continue
                tab.session?.setActive(false); tab.session?.close()
                tabs.removeAt(index)
                val updated = mutableMapOf<Int, Long>()
                for ((oldIdx, time) in pendingDeletions) {
                    updated[if (oldIdx > index) oldIdx - 1 else oldIdx] = time
                }
                pendingDeletions.clear()
                pendingDeletions.putAll(updated)
                
                if (tabs.isEmpty()) {
                    currentTabIndex = -1
                    selectedDomain = ""
                } else if (currentTabIndex > index) {
                    currentTabIndex--
                } else if (currentTabIndex == index && tabs.isNotEmpty()) {
                    currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
                }
                if (highlightedTabIndex == index) highlightedTabIndex = -1
                else if (highlightedTabIndex > index) highlightedTabIndex--
                if (selectedDomain.isNotBlank()) {
                    val dg = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
                    if (!dg.containsKey(selectedDomain)) selectedDomain = ""
                }
            }
        }
    }

    fun loadFavicon(domain: String) {
        if (!faviconBitmaps.containsKey(domain) && faviconLoading[domain] != true) {
            faviconLoading[domain] = true
            scope.launch {
                faviconBitmaps[domain] = FaviconCache.getFaviconBitmap(context, domain)
                    ?: FaviconCache.downloadAndCacheFavicon(context, domain)
                faviconLoading[domain] = false
            }
        }
    }

    fun loadTabFavicon(domain: String) {
        if (domain.isNotBlank() && !tabFavicons.containsKey(domain) && tabFaviconLoading[domain] != true) {
            tabFaviconLoading[domain] = true
            scope.launch {
                tabFavicons[domain] = FaviconCache.getFaviconBitmap(context, domain)
                    ?: FaviconCache.downloadAndCacheFavicon(context, domain)
                tabFaviconLoading[domain] = false
            }
        }
    }

    LaunchedEffect(showTabManager, currentTabIndex) {
        if (showTabManager) {
            homeSession.setActive(false); tabs.forEach { it.session?.setActive(false) }
        } else {
            homeSession.setActive(currentTabIndex == -1)
            if (currentTabIndex >= 0) manageTabLifecycle(currentTabIndex)
        }
    }

    fun createBackgroundTab(url: String) {
        val s = GeckoSession().apply {
            applySettingsToSession(this); open(runtime); loadUri(url); setActive(false)
        }
        tabs.add(TabState().apply {
            session = s; this.url = url; isBlankTab = false; isDiscarded = false
            lastUpdated = System.currentTimeMillis(); setupDelegates(this)
        })
        manageTabLifecycle(currentTabIndex)
    }

    fun createForegroundTab(url: String) {
        val s = GeckoSession().apply {
            applySettingsToSession(this); open(runtime); loadUri(url)
        }
        tabs.add(TabState().apply {
            session = s; isBlankTab = false; isDiscarded = false
            lastUpdated = System.currentTimeMillis(); setupDelegates(this)
        })
        currentTabIndex = tabs.lastIndex; manageTabLifecycle(currentTabIndex)
    }

    fun requestDeleteTab(index: Int) {
        if (index >= 0 && index < tabs.size) pendingDeletions[index] = System.currentTimeMillis()
    }

    fun undoDeleteTab(index: Int) { pendingDeletions.remove(index) }

// END OF PART 6/10
// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — GreyBrowser() Download Functions ===
// ═══════════════════════════════════════════════════════════════════

    fun getDownloadDir(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Grey")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun addDownload(item: DownloadItem) { activeDownloads.add(item) }

    fun downloadM3U8(item: DownloadItem) {
        item.state = DownloadState.DOWNLOADING
        currentDownloadId = item.id
        startDownloadService(context)
        
        thread(name = "m3u8-${item.id}") {
            try {
                val url = URL(item.url)
                val conn = url.openConnection() as HttpURLConnection
                if (item.referer.isNotBlank()) conn.setRequestProperty("Referer", item.referer)
                conn.connect()
                val playlist = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                
                val baseUrl = item.url.substringBeforeLast("/")
                val segments = playlist.lines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { seg -> if (seg.startsWith("http")) seg.trim() else "$baseUrl/${seg.trim()}" }
                
                if (segments.isEmpty()) {
                    item.state = DownloadState.FAILED
                    if (showDownloadManager) downloadUpdateTrigger++
                    return@thread
                }
                
                val tempDir = File(context.cacheDir, "m3u8_${item.id}")
                tempDir.mkdirs()
                val segmentFiles = mutableListOf<File>()
                
                for ((i, segUrl) in segments.withIndex()) {
                    while (item.state == DownloadState.PAUSED) {
                        Thread.sleep(500)
                        if (item.state == DownloadState.FAILED) {
                            tempDir.deleteRecursively()
                            return@thread
                        }
                    }
                    if (item.state == DownloadState.FAILED) {
                        tempDir.deleteRecursively()
                        return@thread
                    }
                    
                    val segFile = File(tempDir, "seg_${i.toString().padStart(5, '0')}.ts")
                    try {
                        val segConn = URL(segUrl).openConnection() as HttpURLConnection
                        segConn.connectTimeout = 10000; segConn.readTimeout = 10000
                        if (item.referer.isNotBlank()) segConn.setRequestProperty("Referer", item.referer)
                        
                        segConn.inputStream.use { input ->
                            segFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    networkLimiter.acquire(bytesRead.toLong())
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                        segmentFiles.add(segFile)
                        item.progress = ((i + 1) * 100) / segments.size
                        if (showDownloadManager) downloadUpdateTrigger++
                    } catch (e: Exception) {
                        // Segment failed, continue with others
                    }
                }
                
                if (segmentFiles.isEmpty()) {
                    item.state = DownloadState.FAILED
                    if (showDownloadManager) downloadUpdateTrigger++
                    tempDir.deleteRecursively()
                    return@thread
                }
                
                val outputFile = File(getDownloadDir(), item.fileName.replace(".m3u8", ".ts"))
                FileOutputStream(outputFile).use { out ->
                    for (seg in segmentFiles) {
                        seg.inputStream().use { it.copyTo(out) }
                    }
                }
                
                item.tempFile = outputFile
                item.state = DownloadState.COMPLETED
                item.progress = 100
                if (showDownloadManager) downloadUpdateTrigger++
                tempDir.deleteRecursively()
            } catch (e: Exception) {
                if (item.state != DownloadState.PAUSED) {
                    item.state = DownloadState.FAILED
                    if (showDownloadManager) downloadUpdateTrigger++
                }
            } finally {
                if (currentDownloadId == item.id) {
                    currentDownloadId = null
                    stopDownloadService(context)
                }
            }
        }
    }

    fun startDownload(id: String) {
        val item = activeDownloads.find { it.id == id } ?: return
        if (currentDownloadId != null && currentDownloadId != id) return
        
        if (item.url.contains(".m3u8")) { downloadM3U8(item); return }
        
        item.state = DownloadState.DOWNLOADING
        currentDownloadId = id
        startDownloadService(context)
        
        thread(name = "download-$id") {
            try {
                val url = URL(item.url)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                if (item.referer.isNotBlank()) conn.setRequestProperty("Referer", item.referer)
                if (item.cookies.isNotBlank()) conn.setRequestProperty("Cookie", item.cookies)
                conn.connect()
                
                val totalSize = conn.contentLength.toLong()
                val input = BufferedInputStream(conn.inputStream)
                val outputFile = File(getDownloadDir(), item.fileName)
                item.tempFile = outputFile
                val output = FileOutputStream(outputFile)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                val startTime = System.currentTimeMillis()
                var lastUpdateTime = startTime
                
                while (true) {
                    while (item.state == DownloadState.PAUSED) {
                        Thread.sleep(500)
                        if (item.state == DownloadState.FAILED) {
                            try { input.close() } catch (_: Exception) {}
                            try { output.close() } catch (_: Exception) {}
                            try { conn.disconnect() } catch (_: Exception) {}
                            return@thread
                        }
                    }
                    if (item.state == DownloadState.FAILED) {
                        try { input.close() } catch (_: Exception) {}
                        try { output.close() } catch (_: Exception) {}
                        try { conn.disconnect() } catch (_: Exception) {}
                        return@thread
                    }
                    
                    networkLimiter.acquire(buffer.size.toLong())
                    
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 500) {
                        val elapsed = (now - startTime) / 1000f
                        val speedBps = if (elapsed > 0) totalRead / elapsed else 0f
                        item.downloadedBytes = totalRead
                        item.progress = if (totalSize > 0) ((totalRead * 100) / totalSize).toInt() else 0
                        item.speed = when {
                            speedBps > 1_000_000 -> "%.1f MB/s".format(speedBps / 1_000_000)
                            speedBps > 1_000 -> "%.0f KB/s".format(speedBps / 1_000)
                            else -> "%.0f B/s".format(speedBps)
                        }
                        if (showDownloadManager) downloadUpdateTrigger++
                        lastUpdateTime = now
                    }
                }
                input.close()
                output.close()
                conn.disconnect()
                if (item.state != DownloadState.PAUSED) {
                    item.state = DownloadState.COMPLETED
                    item.progress = 100
                    if (showDownloadManager) downloadUpdateTrigger++
                }
            } catch (e: Exception) {
                if (item.state != DownloadState.PAUSED) {
                    item.state = DownloadState.FAILED
                    if (showDownloadManager) downloadUpdateTrigger++
                }
            } finally {
                if (currentDownloadId == id) {
                    currentDownloadId = null
                    val next = activeDownloads.firstOrNull { it.state == DownloadState.QUEUED }
                    if (next != null) {
                        startDownload(next.id)
                    } else {
                        stopDownloadService(context)
                    }
                }
            }
        }
    }

    fun pauseDownload(id: String) { 
        activeDownloads.find { it.id == id }?.state = DownloadState.PAUSED
        if (showDownloadManager) downloadUpdateTrigger++
    }
    
    fun resumeDownload(id: String) {
        val item = activeDownloads.find { it.id == id } ?: return
        if (currentDownloadId != null && currentDownloadId != id) {
            item.state = DownloadState.QUEUED
        } else {
            item.state = DownloadState.DOWNLOADING
            startDownload(id)
        }
        if (showDownloadManager) downloadUpdateTrigger++
    }
    
    fun deleteDownload(id: String) {
        val item = activeDownloads.find { it.id == id } ?: return
        val wasActive = currentDownloadId == id
        item.tempFile?.delete()
        activeDownloads.remove(item)
        if (wasActive) {
            currentDownloadId = null
            val next = activeDownloads.firstOrNull { it.state == DownloadState.QUEUED }
            if (next != null) {
                startDownload(next.id)
            } else {
                stopDownloadService(context)
            }
        }
    }

    BackHandler {
        if (currentTab.isBlankTab) { if (tabs.isNotEmpty()) currentTabIndex = tabs.lastIndex else activity?.finish() }
        else currentTab.session?.goBack()
    }

    @Composable
    fun GeckoViewBox() {
        val s = currentTab.session
        if (s != null) {
            AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(s) } }, update = { gv -> gv.setSession(s) }, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF121212)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        }
    }

// END OF PART 7/10
// ═══════════════════════════════════════════════════════════════════
// === PART 8/10 — GreyBrowser() Dialogs, Context Menu, Tab Manager, URL Bar, Menu, Toast ===
// ═══════════════════════════════════════════════════════════════════

    if (showDownloadEditor) {
        var editName by remember { mutableStateOf(downloadEditorName) }
        AlertDialog(
            onDismissRequest = { showDownloadEditor = false },
            title = { Text("Download", color = Color.White, fontSize = 18.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName, onValueChange = { editName = it }, singleLine = true,
                        label = { Text("Name", color = Color.Gray) },
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)),
                        shape = RectangleShape, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Size: $downloadEditorSize", color = Color.Gray, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Location: /Download/Grey/", color = Color.Gray, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row {
                    TextButton({
                        addDownload(DownloadItem(url = downloadEditorUrl, fileName = editName.ifBlank { downloadEditorName }, isVideo = downloadEditorIsVideo, referer = currentTab.url, state = DownloadState.QUEUED))
                        showDownloadEditor = false
                        showToast("Added to downloads")
                    }) { Text("Add", color = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    TextButton({
                        val item = DownloadItem(url = downloadEditorUrl, fileName = editName.ifBlank { downloadEditorName }, isVideo = downloadEditorIsVideo, referer = currentTab.url)
                        addDownload(item)
                        if (currentDownloadId == null) {
                            startDownload(item.id)
                        }
                        showDownloadEditor = false
                        showToast("Download started")
                    }) { Text("Start", color = Color.White) }
                }
            },
            dismissButton = { TextButton({ showDownloadEditor = false }) { Text("Cancel", color = Color.White) } },
            containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
        )
    }

    if (showConfirmDialog) {
        AlertDialog(onDismissRequest = { showConfirmDialog = false; confirmAction = null }, title = { Text(confirmTitle, color = Color.White, fontSize = 18.sp) }, text = { Text(confirmMessage, color = Color.Gray, fontSize = 14.sp) }, confirmButton = { TextButton({ val a = confirmAction; showConfirmDialog = false; confirmAction = null; a?.invoke() }) { Text("Confirm", color = Color.White) } }, dismissButton = { TextButton({ showConfirmDialog = false; confirmAction = null }) { Text("Cancel", color = Color.White) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp)
    }

    if (showSettings) { SettingsDialog(prefs, { showSettings = false }) { applySettingsToSession(homeSession); tabs.forEach { it.session?.let { s -> applySettingsToSession(s) } } } }
    if (showExtensions) { ExtensionsUI(extensionManager = extensionManager, extensions = extensions, onDismiss = { showExtensions = false }) }

    if (showHistory) {
        HistoryUI(history = history, onDismiss = { showHistory = false }, onOpenUrl = { url -> createForegroundTab(url) }, faviconBitmaps = faviconBitmaps, loadFavicon = { loadFavicon(it) })
    }

    if (showBookmarks) {
        BookmarksUI(bookmarks = bookmarks, onDismiss = { showBookmarks = false }, onOpenUrl = { url -> createForegroundTab(url) }, onDelete = { id -> bookmarks.removeAll { it.id == id }; showToast("Bookmark deleted") }, faviconBitmaps = faviconBitmaps, loadFavicon = { loadFavicon(it) })
    }

    if (showDownloadManager) {
        DownloadManagerUI(
            downloads = activeDownloads,
            currentDownloadId = currentDownloadId,
            onDismiss = { showDownloadManager = false },
            onStart = { startDownload(it) },
            onPause = { pauseDownload(it) },
            onResume = { resumeDownload(it) },
            onDelete = { deleteDownload(it) },
            networkLimiter = networkLimiter,
            updateTrigger = downloadUpdateTrigger
        )
    }

    if (showContextMenu && contextMenuUri != null) {
        val clipMgr = clipboardManager; val ctxUri = contextMenuUri
        Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.border(1.dp, Color.White, RectangleShape).background(Color(0xFF1E1E1E)).width(IntrinsicSize.Max)) {
                ContextMenuItem("New Tab") { createForegroundTab(ctxUri!!); showContextMenu = false }
                ContextMenuItem("Open in Background") { createBackgroundTab(ctxUri!!); showContextMenu = false }
                ContextMenuItem("Copy link") { clipMgr.setText(AnnotatedString(ctxUri!!)); showContextMenu = false; showToast("Link copied") }
            }
        }
    }

    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }.filter { it.key.isNotBlank() }
        val sortedDomains = domainGroups.keys.sortedWith(compareByDescending<String> { pinnedDomains.contains(it) }.thenBy { d: String -> domainGroups[d]?.firstOrNull()?.let { t -> tabs.indexOf(t) } ?: Int.MAX_VALUE })
        val pinnedSorted = sortedDomains.filter { pinnedDomains.contains(it) }; val unpinnedSorted = sortedDomains.filter { !pinnedDomains.contains(it) }
        val highlightDomain = if (currentTab.isBlankTab && highlightedTabIndex >= 0) getDomainName(tabs[highlightedTabIndex].url) else if (!currentTab.isBlankTab) getDomainName(currentTab.url) else ""

        LaunchedEffect(Unit) { selectedDomain = ""; if (highlightDomain.isNotBlank()) { blinkTargetDomain.value = highlightDomain; showBlink = true; delay(1500); showBlink = false; blinkTargetDomain.value = "" } }

        Popup(alignment = Alignment.TopStart, onDismissRequest = { showTabManager = false }, properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ showTabManager = false }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                        Spacer(Modifier.width(4.dp)); Text("Tabs", color = Color.White, fontSize = 18.sp)
                        if (tabs.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${tabs.size})", color = Color.Gray, fontSize = 14.sp) }
                    }

                    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                        val groupListState = rememberLazyListState()
                        val allSidebarItems = listOf("__ALL__") + pinnedSorted + unpinnedSorted
                        LaunchedEffect(allSidebarItems, selectedDomain) { val idx = if (selectedDomain.isBlank()) 0 else allSidebarItems.indexOf(selectedDomain); if (idx >= 0) groupListState.scrollToItem(idx) }

                        LazyColumn(state = groupListState, modifier = Modifier.width(56.dp).fillMaxHeight().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            item { AllGroupChip(isSelected = selectedDomain.isBlank(), tabCount = tabs.size, onClick = { selectedDomain = "" }) }
                            items(pinnedSorted) { domain: String -> SidebarGroupChip(domain, domain == selectedDomain, domainGroups[domain]?.size ?: 0, { selectedDomain = domain }, faviconBitmaps[domain], { loadFavicon(domain) }, showBlink && blinkTargetDomain.value == domain, true) }
                            items(unpinnedSorted) { domain: String -> SidebarGroupChip(domain, domain == selectedDomain, domainGroups[domain]?.size ?: 0, { selectedDomain = domain }, faviconBitmaps[domain], { loadFavicon(domain) }, showBlink && blinkTargetDomain.value == domain, false) }
                        }

                        VerticalDivider(color = Color.DarkGray, modifier = Modifier.fillMaxHeight().width(1.dp))

                        val tabsToShow = if (selectedDomain.isBlank()) tabs.toList() else domainGroups[selectedDomain] ?: emptyList()
                        val tabListState = rememberLazyListState()
                        val scrollTarget = if (currentTabIndex >= 0) currentTab else if (highlightedTabIndex >= 0) tabs.getOrNull(highlightedTabIndex) else null
                        LaunchedEffect(selectedDomain) { val idx = tabsToShow.indexOf(scrollTarget); if (idx >= 0) tabListState.scrollToItem(idx) }

                        if (tabs.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("No open tabs", color = Color.Gray, fontSize = 16.sp); Spacer(Modifier.height(8.dp)); Text("Tap 'New Tab' to start browsing", color = Color.Gray.copy(alpha = 0.7f), fontSize = 14.sp) } }
                        } else {
                            LazyColumn(state = tabListState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (tabsToShow.isEmpty()) { item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No tabs in this group", color = Color.Gray, fontSize = 14.sp) } } }
                                else {
                                    items(tabsToShow) { tab: TabState ->
                                        val tabIndex = tabs.indexOf(tab)
                                        val isHighlighted = tabIndex == currentTabIndex || (currentTabIndex == -1 && tabIndex == highlightedTabIndex)
                                        val isPending = pendingDeletions.containsKey(tabIndex)
                                        val tabDomain = getDomainName(tab.url)
                                        LaunchedEffect(tab.url) { loadTabFavicon(tabDomain) }
                                        val tabFav = tabFavicons[tabDomain]

                                        Surface(Modifier.fillMaxWidth().clickable(enabled = !isPending) { currentTabIndex = tabIndex; showTabManager = false }.border(0.5.dp, Color.DarkGray, RectangleShape), color = if (isPending) Color.Red.copy(alpha = 0.3f) else if (isHighlighted) Color.White else Color.Transparent) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (tabFav != null) Image(tabFav.asImageBitmap(), tabDomain, Modifier.size(16.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                                                else Box(Modifier.size(16.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) { Text(tabDomain.take(1).uppercase(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                                                Spacer(Modifier.width(8.dp))
                                                Text(if (tab.title == "New Tab" || tab.title.isBlank()) tab.url else tab.title, color = if (isPending) Color.White else if (isHighlighted) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                                if (isPending) IconButton({ undoDeleteTab(tabIndex) }) { Icon(Icons.Default.Undo, "Undo", tint = Color.White, modifier = Modifier.size(18.dp)) }
                                                else IconButton({ requestDeleteTab(tabIndex) }) { Icon(Icons.Default.Close, "Close", tint = if (isHighlighted) Color.Black else Color.White, modifier = Modifier.size(18.dp)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                        OutlinedButton(onClick = { currentTabIndex = -1; showTabManager = false }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) { Icon(Icons.Default.Add, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("New Tab", color = Color.White) }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            val hasSelection = selectedDomain.isNotBlank(); val isPinned = pinnedDomains.contains(selectedDomain)
                            val faintColor = Color.White.copy(alpha = 0.3f); val activeColor = Color.White
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = if (isPinned) "Unpin Group?" else "Pin Group?"; confirmMessage = "Are you sure?"; confirmAction = { if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain) }; showConfirmDialog = true } }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasSelection) activeColor else faintColor), border = BorderStroke(1.dp, if (hasSelection) activeColor else faintColor)) {
                                Text(if (isPinned) "Unpin Group" else "Pin Group", fontSize = 13.sp, color = if (hasSelection) activeColor else faintColor)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { if (hasSelection) { confirmTitle = "Delete Group?"; confirmMessage = "All tabs in this group will be lost."; confirmAction = { 
                                val toRemove = (domainGroups[selectedDomain] ?: emptyList()).toList()
                                toRemove.forEach { tab ->
                                    val idx = tabs.indexOf(tab)
                                    if (idx >= 0) pendingDeletions.remove(idx)
                                    tab.session?.setActive(false); tab.session?.close()
                                }
                                tabs.removeAll(toRemove)
                                if (tabs.isEmpty()) { currentTabIndex = -1; highlightedTabIndex = -1; selectedDomain = "" }
                                else { currentTabIndex = currentTabIndex.coerceIn(0, tabs.lastIndex); selectedDomain = "" }
                            }; showConfirmDialog = true } }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasSelection) activeColor else faintColor), border = BorderStroke(1.dp, if (hasSelection) activeColor else faintColor)) {
                                Text("Delete Group", fontSize = 13.sp, color = if (hasSelection) activeColor else faintColor)
                            }
                        }
                    }
                }
            }
        }
    }

    var urlInput by remember { mutableStateOf(TextFieldValue(if (currentTab.url == "about:blank") "" else currentTab.url)) }
    var isUrlFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentTab, currentTab.url) { if (!isUrlFocused) { urlInput = TextFieldValue(if (currentTab.url == "about:blank") "" else currentTab.url) } }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ showTabManager = true; showToast("Tab manager opened") }) { Icon(Icons.Default.Tab, "Tabs", tint = Color.White) }
                    val isLoading = currentTab.progress in 1..99
                    OutlinedTextField(
                        value = urlInput, onValueChange = { urlInput = it }, singleLine = true,
                        placeholder = { Text(if (currentTab.isBlankTab) "Search or enter URL" else currentTab.url.take(50), color = Color.White.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp).background(Color(0xFF1E1E1E)).focusRequester(focusRequester).onFocusChanged { isUrlFocused = it.isFocused }.drawBehind { if (isLoading) drawRect(Color.White, size = size.copy(width = size.width * currentTab.progress / 100f)) },
                        textStyle = TextStyle(if (isLoading) Color.Gray else Color.White, 16.sp), shape = RectangleShape,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { val input = urlInput.text; if (input.isNotBlank()) { focusManager.clearFocus(); val uri = resolveUrl(input); if (currentTab.isBlankTab) createForegroundTab(uri) else currentTab.session?.loadUri(uri) } }),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = if (isLoading) Color.Gray else Color.White),
                        trailingIcon = { if (isLoading) IconButton({ currentTab.session?.stop() }) { Icon(Icons.Default.Close, "Stop", tint = Color.White) } else IconButton({ urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length)); focusRequester.requestFocus() }) { Icon(Icons.Default.SelectAll, "Select all", tint = Color.White) } }
                    )
                    IconButton({ currentTabIndex = -1 }) { Icon(Icons.Default.Add, "New Tab", tint = Color.White) }
                    if (detectedVideos.isNotEmpty()) {
                        Box {
                            IconButton({ showVideoDropdown = !showVideoDropdown }) { Icon(Icons.Default.VideoLibrary, "Videos", tint = Color.White) }
                            if (showVideoDropdown) {
                                DropdownMenu(expanded = showVideoDropdown, onDismissRequest = { showVideoDropdown = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
                                    detectedVideos.take(10).forEach { (url, _) ->
                                        val shortName = url.substringAfterLast("/").substringBefore("?").take(50)
                                        DropdownMenuItem(text = { Text(shortName, color = Color.White, fontSize = 13.sp) }, onClick = {
                                            showVideoDropdown = false; downloadEditorUrl = url
                                            downloadEditorName = currentTab.title; downloadEditorSize = "Unknown"
                                            downloadEditorIsVideo = true; showDownloadEditor = true
                                        })
                                    }
                                }
                            }
                        }
                    }
                    Box {
                        IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
                            DropdownMenuItem(text = { Text("Add to Bookmark", color = Color.White) }, onClick = {
                                showMenu = false
                                val url = currentTab.url
                                if (url != "about:blank" && url.isNotBlank()) {
                                    bookmarks.removeAll { it.url == url }
                                    bookmarks.add(Bookmark(url = url, title = currentTab.title.ifBlank { url }))
                                    showToast("Added to bookmarks")
                                }
                            })
                            DropdownMenuItem(text = { Text("Bookmarks", color = Color.White) }, onClick = { showMenu = false; showBookmarks = true })
                            DropdownMenuItem(text = { Text("Downloads", color = Color.White) }, onClick = { showMenu = false; showDownloadManager = true })
                            DropdownMenuItem(text = { Text("History", color = Color.White) }, onClick = { showMenu = false; showHistory = true })
                            DropdownMenuItem(text = { Text("Extensions", color = Color.White) }, onClick = { showMenu = false; showExtensions = true })
                            DropdownMenuItem(text = { Text("Settings", color = Color.White) }, onClick = { showMenu = false; showSettings = true })
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { GeckoViewBox() }
            }

            // ── Toast Overlay ──────────────────────────────────
            if (showToast) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        modifier = Modifier.padding(bottom = 80.dp),
                        color = Color.White.copy(alpha = 0.9f),
                        shape = RectangleShape
                    ) {
                        Text(
                            toastMessage,
                            color = Color.Black,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

// END OF PART 8/10
// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — DownloadManagerUI Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun DownloadManagerUI(
    downloads: List<DownloadItem>,
    currentDownloadId: String?,
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDelete: (String) -> Unit,
    networkLimiter: NetworkSpeedLimiter,
    updateTrigger: Int
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var downloadToDelete by remember { mutableStateOf<String?>(null) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    if (showDeleteConfirm && downloadToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; downloadToDelete = null },
            title = { Text("Delete Download?", color = Color.White, fontSize = 18.sp) },
            text = { Text("This cannot be undone.", color = Color.Gray, fontSize = 14.sp) },
            confirmButton = {
                TextButton({
                    onDelete(downloadToDelete!!)
                    showDeleteConfirm = false
                    downloadToDelete = null
                }) { Text("Delete", color = Color.White) }
            },
            dismissButton = {
                TextButton({ showDeleteConfirm = false; downloadToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            shape = RectangleShape,
            tonalElevation = 0.dp
        )
    }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)),
            color = Color(0xFF1E1E1E)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Downloads", color = Color.White, fontSize = 18.sp)
                    if (downloads.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("(${downloads.size})", color = Color.Gray, fontSize = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton({ showSpeedMenu = true }) {
                            Text(formatSpeedLimit(networkLimiter.maxBytesPerSecond), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            modifier = Modifier.border(1.dp, Color.White, RectangleShape),
                            containerColor = Color(0xFF1E1E1E),
                            shape = RectangleShape
                        ) {
                            SPEED_LIMITS.forEach { limit ->
                                DropdownMenuItem(
                                    text = { Text(formatSpeedLimit(limit), color = if (limit == networkLimiter.maxBytesPerSecond) Color.White else Color.Gray, fontSize = 13.sp) },
                                    onClick = { networkLimiter.maxBytesPerSecond = limit; showSpeedMenu = false }
                                )
                            }
                        }
                    }
                }

                if (downloads.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No downloads", color = Color.Gray, fontSize = 16.sp) }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(downloads) { item ->
                            val currentState = remember(updateTrigger) { item.state }
                            val currentProgress = remember(updateTrigger) { item.progress }
                            val currentSpeed = remember(updateTrigger) { item.speed }

                            Surface(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp).border(0.5.dp, Color.DarkGray, RectangleShape),
                                color = Color.Transparent
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(item.fileName, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))

                                    when (currentState) {
                                        DownloadState.QUEUED -> Text("Queued", color = Color.Gray, fontSize = 12.sp)
                                        DownloadState.DOWNLOADING -> {
                                            LinearProgressIndicator(progress = { currentProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.DarkGray)
                                            Spacer(Modifier.height(4.dp))
                                            Text("${currentProgress}% · ${currentSpeed}", color = Color.White, fontSize = 12.sp)
                                        }
                                        DownloadState.PAUSED -> Text("Paused · ${currentProgress}%", color = Color(0xFFFFA500), fontSize = 12.sp)
                                        DownloadState.COMPLETED -> Text("Completed", color = Color(0xFF4CAF50), fontSize = 12.sp)
                                        DownloadState.FAILED -> Text("Failed", color = Color.Red, fontSize = 12.sp)
                                    }

                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        when (currentState) {
                                            DownloadState.QUEUED -> {
                                                if (currentDownloadId == null) {
                                                    TextButton({ onStart(item.id) }) { Text("Start", color = Color.White, fontSize = 13.sp) }
                                                } else {
                                                    Text("In Queue", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                                }
                                            }
                                            DownloadState.DOWNLOADING -> TextButton({ onPause(item.id) }) { Text("Pause", color = Color.White, fontSize = 13.sp) }
                                            DownloadState.PAUSED -> TextButton({ onResume(item.id) }) { Text("Resume", color = Color.White, fontSize = 13.sp) }
                                            else -> {}
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        TextButton({ downloadToDelete = item.id; showDeleteConfirm = true }) { Text("Delete", color = Color.Red, fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// END OF PART 9/10
// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — ExtensionsUI, BookmarksUI, HistoryUI, Settings, Helpers ===
// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════
// ── EXTENSIONS UI ────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ExtensionsUI(
    extensionManager: ExtensionManager,
    extensions: MutableList<ExtensionItem>,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var storeResults by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf("") }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.width(4.dp))
                    Text("Extensions", color = Color.White, fontSize = 18.sp)
                }

                // Tab row
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.weight(1f),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selectedTab == 0) Color.White else Color.Gray),
                        border = BorderStroke(1.dp, if (selectedTab == 0) Color.White else Color.DarkGray)
                    ) { Text("Discover", fontSize = 13.sp) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.weight(1f),
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selectedTab == 1) Color.White else Color.Gray),
                        border = BorderStroke(1.dp, if (selectedTab == 1) Color.White else Color.DarkGray)
                    ) { Text("Installed", fontSize = 13.sp) }
                }

                if (selectedTab == 0) {
                    // ── Discover Tab ──────────────────────────
                    OutlinedTextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, singleLine = true,
                        placeholder = { Text("Search extensions...", color = Color.Gray.copy(alpha = 0.5f)) },
                        leadingIcon = { Icon(Icons.Default.Search, "Search", tint = Color.Gray) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)),
                        shape = RectangleShape,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (searchQuery.isNotBlank()) {
                                isSearching = true
                                extensionManager.searchStore(searchQuery) { results ->
                                    storeResults = results
                                    isSearching = false
                                }
                            }
                        })
                    )

                    if (installMessage.isNotBlank()) {
                        Text(installMessage, color = Color(0xFF4CAF50), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }

                    if (isSearching) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                            if (storeResults.isEmpty() && searchQuery.isBlank()) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        Text("Search for extensions to add", color = Color.Gray, fontSize = 14.sp)
                                    }
                                }
                            }
                            items(storeResults) { ext ->
                                val alreadyInstalled = extensions.any { it.id == ext.id }
                                Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).border(0.5.dp, Color.DarkGray, RectangleShape), color = Color.Transparent) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(ext.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                            Text(ext.description.take(100), color = Color.Gray.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (alreadyInstalled) {
                                            Text("Installed", color = Color.Gray, fontSize = 12.sp)
                                        } else {
                                            TextButton({
                                                extensionManager.searchStore(ext.name) { results ->
                                                    val fullExt = results.firstOrNull { it.id == ext.id }
                                                    if (fullExt != null) {
                                                        val xpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/${fullExt.id}/addon-${fullExt.id}-latest.xpi"
                                                        extensionManager.installFromUrl(xpiUrl) { success, msg ->
                                                            if (success) {
                                                                extensions.add(ext.copy(enabled = true))
                                                                installMessage = "${ext.name} installed!"
                                                            } else {
                                                                installMessage = "Failed: $msg"
                                                            }
                                                        }
                                                    }
                                                }
                                            }) { Text("Install", color = Color.White, fontSize = 13.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ── Installed Tab ─────────────────────────
                    if (extensions.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No extensions installed", color = Color.Gray, fontSize = 14.sp) }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                            items(extensions.toList()) { ext ->
                                Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).border(0.5.dp, Color.DarkGray, RectangleShape), color = Color.Transparent) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(ext.name, color = if (ext.enabled) Color.White else Color.Gray, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                            Text(ext.description.take(80), color = Color.Gray.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Switch(
                                            checked = ext.enabled,
                                            onCheckedChange = { enabled ->
                                                val idx = extensions.indexOfFirst { it.id == ext.id }
                                                if (idx >= 0) {
                                                    extensions[idx] = ext.copy(enabled = enabled)
                                                    extensionManager.setEnabled(ext.id, enabled)
                                                }
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)),
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        IconButton({
                                            extensions.removeAll { it.id == ext.id }
                                            extensionManager.uninstall(ext.id)
                                        }) { Icon(Icons.Default.Delete, "Uninstall", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── BOOKMARKS UI ─────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun BookmarksUI(
    bookmarks: List<Bookmark>,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDelete: (String) -> Unit,
    faviconBitmaps: Map<String, Bitmap?>,
    loadFavicon: (String) -> Unit
) {
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.width(4.dp))
                    Text("Bookmarks", color = Color.White, fontSize = 18.sp)
                    if (bookmarks.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${bookmarks.size})", color = Color.Gray, fontSize = 14.sp) }
                }

                if (bookmarks.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No bookmarks", color = Color.Gray, fontSize = 16.sp) }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(bookmarks.reversed()) { item ->
                            val domain = getDomainName(item.url)
                            LaunchedEffect(item.url) { loadFavicon(domain) }
                            val fav = faviconBitmaps[domain]

                            Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).border(0.5.dp, Color.DarkGray, RectangleShape), color = Color.Transparent) {
                                Row(Modifier.fillMaxWidth().padding(12.dp).clickable { onOpenUrl(item.url); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                                    if (fav != null) Image(fav.asImageBitmap(), domain, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                                    else Box(Modifier.size(20.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title.ifBlank { item.url }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.url, color = Color.Gray.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton({ onDelete(item.id) }) { Icon(Icons.Default.Close, "Delete", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── SETTINGS ─────────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var js by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var tp by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cb by remember { mutableIntStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var am by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }
    val apply = {
        prefs.edit().putBoolean("javascript.enabled", js).putBoolean("privacy.trackingprotection.enabled", tp).putInt("network.cookie.cookieBehavior", cb).putBoolean("media.autoplay.enabled", am).apply()
        onSettingsApplied(); onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Settings", color = Color.White, fontSize = 20.sp) },
        text = {
            Column {
                SettingSwitch("JavaScript", js) { js = it }
                SettingSwitch("Tracking Protection", tp) { tp = it }
                Text("Cookie Behavior", color = Color.White, fontSize = 14.sp)
                CookieBehaviorSelector(cb) { cb = it }
                SettingSwitch("Autoplay Media", am) { am = it }
            }
        },
        confirmButton = { TextButton(apply) { Text("OK", color = Color.White) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, textContentColor = Color.White, shape = RectangleShape, tonalElevation = 0.dp
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF444444), uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Accept all" to 0, "Reject all" to 1, "Only from visited" to 2)
    val st = options.firstOrNull { it.second == current }?.first ?: "Accept all"
    Box {
        OutlinedTextField(value = st, onValueChange = {}, readOnly = true, trailingIcon = { Icon(Icons.Default.Close, null, tint = Color.White) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textStyle = TextStyle(color = Color.White, fontSize = 14.sp), shape = RectangleShape, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)))
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E), shape = RectangleShape) {
            options.forEach { (label, value) -> DropdownMenuItem(text = { Text(text = label, color = Color.White) }, onClick = { onChange(value); expanded = false }) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── HISTORY UI ───────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HistoryUI(
    history: List<HistoryItem>,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    faviconBitmaps: Map<String, Bitmap?>,
    loadFavicon: (String) -> Unit
) {
    Popup(alignment = Alignment.TopStart, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF1E1E1E)), color = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onDismiss() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.width(4.dp)); Text("History", color = Color.White, fontSize = 18.sp)
                    if (history.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("(${history.size})", color = Color.Gray, fontSize = 14.sp) }
                }
                if (history.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No history", color = Color.Gray, fontSize = 16.sp) }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(history.reversed()) { item ->
                            val domain = getDomainName(item.url)
                            LaunchedEffect(item.url) { loadFavicon(domain) }
                            val fav = faviconBitmaps[domain]
                            Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).border(0.5.dp, Color.DarkGray, RectangleShape), color = Color.Transparent) {
                                Row(Modifier.fillMaxWidth().padding(12.dp).clickable { onOpenUrl(item.url); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                                    if (fav != null) Image(fav.asImageBitmap(), domain, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                                    else Box(Modifier.size(20.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title.ifBlank { item.url }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.url, color = Color.Gray.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ── COMPOSABLE HELPERS ────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════

@Composable
fun AllGroupChip(isSelected: Boolean, tabCount: Int, onClick: () -> Unit) {
    val bg = if (isSelected) Color.White else Color.Transparent
    val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, borderColor, RectangleShape), color = bg) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("All", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Box(Modifier.background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun SidebarGroupChip(domain: String, isSelected: Boolean, tabCount: Int, onClick: () -> Unit, favicon: Bitmap?, onAppear: () -> Unit, isBlinking: Boolean, isPinned: Boolean) {
    LaunchedEffect(domain) { onAppear() }
    val blinkAlpha = if (isBlinking) {
        val t = rememberInfiniteTransition(label = "blink_$domain")
        t.animateFloat(0.2f, 0.5f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "blinkV")
    } else null
    val bgAlpha = if (isBlinking && blinkAlpha != null) blinkAlpha.value else if (isSelected) 1f else 0f
    val bg = Color.White.copy(alpha = bgAlpha)
    val fg = if (isSelected) Color.Black else Color.White
    val borderColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
    Surface(Modifier.padding(vertical = 4.dp).width(52.dp).clickable { onClick() }.border(0.5.dp, borderColor, RectangleShape), color = bg) {
        Box(Modifier.padding(6.dp)) {
            if (isPinned) Icon(Icons.Default.PushPin, "Pinned", tint = fg, modifier = Modifier.size(12.dp).align(Alignment.TopStart))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(4.dp))
                if (favicon != null) Image(favicon.asImageBitmap(), domain, Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Fit)
                else Box(Modifier.size(24.dp).clip(CircleShape).background(if (isSelected) Color.LightGray else Color.DarkGray), contentAlignment = Alignment.Center) { Text(domain.take(1).uppercase(), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            Box(Modifier.align(Alignment.BottomEnd).background(if (isSelected) Color.LightGray else Color.DarkGray).padding(horizontal = 4.dp, vertical = 1.dp)) { Text(tabCount.toString(), color = fg, fontSize = 9.sp) }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) { Text(text, color = Color.White, fontSize = 16.sp) }
}

// END OF PART 10/10
// END OF FILE - Grey Browser V2.0
// ═══════════════════════════════════════════════════════════════════
