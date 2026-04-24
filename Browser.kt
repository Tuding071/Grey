package com.grey.browser

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GreyBrowser() }
    }
}

// ── Updated Capture Logic ──────────────────────────────────────────
fun captureThumbnail(view: View, activity: Activity, onCaptured: (Bitmap) -> Unit) {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val locationOfViewInWindow = IntArray(2)
    view.getLocationInWindow(locationOfViewInWindow)
    
    try {
        PixelCopy.request(
            activity.window,
            Rect(locationOfViewInWindow[0], locationOfViewInWindow[1], 
                 locationOfViewInWindow[0] + view.width, locationOfViewInWindow[1] + view.height),
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                }
            },
            Handler(Looper.getMainLooper())
        )
    } catch (e: Exception) { e.printStackTrace() }
}

object ThumbnailCacheManager {
    fun saveImage(context: Context, folderName: String, fileName: String, bitmap: Bitmap, limit: Int) {
        val dir = File(context.filesDir, folderName)
        if (!dir.exists()) dir.mkdirs()
        val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val file = File(dir, "$safeFileName.png")
        try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 70, out) }
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size > limit) {
                val toDeleteCount = files.size - limit
                for (i in 0 until toDeleteCount) files[i].delete()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getImage(context: Context, folderName: String, fileName: String): Bitmap? {
        val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val file = File(context.filesDir, "$folderName/$safeFileName.png")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }
}

class TabState(val session: GeckoSession) {
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
}

fun getDomainName(url: String): String = try {
    if (url == "about:blank" || url.isBlank()) "Home"
    else Uri.parse(url).host?.removePrefix("www.") ?: "Unknown"
} catch (e: Exception) { "Unknown" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val activity = context as Activity
    val rootView = LocalView.current
    val clipboardManager = LocalClipboardManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }
    var geckoViewInstance by remember { mutableStateOf<View?>(null) }

    val setupDelegates = { tabState: TabState ->
        tabState.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tabState.url = url
                tabState.progress = 5
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) { tabState.progress = 100 }
            override fun onProgressChange(session: GeckoSession, progress: Int) { tabState.progress = progress }
        }
        tabState.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(s: GeckoSession, url: String?, p: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, gesture: Boolean) {
                if (url != null) tabState.url = url
            }
        }
        tabState.session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) { tabState.title = title ?: "New Tab" }
            override fun onContextMenu(s: GeckoSession, x: Int, y: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                if (element.linkUri != null) { contextMenuUri = element.linkUri; showContextMenu = true }
            }
        }
    }

    val tabs = remember { mutableStateListOf<TabState>().apply {
        val s = GeckoSession().apply { open(runtime); loadUri("about:blank") }
        val t = TabState(s).also { setupDelegates(it) }
        add(t)
    }}

    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showScripting by remember { mutableStateOf(false) }
    var previewTab by remember { mutableStateOf<TabState?>(null) }

    val pinnedDomains = remember { mutableStateListOf<String>() }
    var selectedDomain by remember { mutableStateOf("Home") }
    val currentTab = tabs.getOrNull(currentTabIndex) ?: tabs.first()

    BackHandler {
        if (previewTab != null) previewTab = null
        else if (showTabManager) showTabManager = false
        else if (currentTab.url != "about:blank") currentTab.session.goBack()
        else activity.finish()
    }

    @Composable
    fun GeckoViewBox() {
        AndroidView(
            factory = { ctx -> GeckoView(ctx).apply { setSession(currentTab.session); geckoViewInstance = this } },
            update = { view -> view.setSession(currentTab.session); geckoViewInstance = view },
            modifier = Modifier.fillMaxSize()
        )
    }

    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }
        val sortedDomains = domainGroups.keys.sortedWith(compareByDescending<String> { pinnedDomains.contains(it) }.thenByDescending { d -> domainGroups[d]?.maxOfOrNull { it.lastUpdated } ?: 0L })

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showTabManager = false }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                    Text("Tabs", color = Color.White, fontSize = 20.sp)
                }

                // Favicons/Groups Row
                LazyRow(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp)) {
                    items(sortedDomains) { domain ->
                        val isSelected = domain == selectedDomain
                        Surface(
                            modifier = Modifier.padding(end = 8.dp).clickable { selectedDomain = domain }.border(if (isSelected) 1.dp else 0.dp, Color.White, RectangleShape),
                            color = if (isSelected) Color(0xFF2A2A2A) else Color.Transparent
                        ) {
                            Text(domain, color = Color.White, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                        }
                    }
                }

                // Tab List
                val tabsToShow = domainGroups[selectedDomain] ?: emptyList()
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(tabsToShow) { tab ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { previewTab = tab }.border(1.dp, Color.DarkGray, RectangleShape),
                            color = Color(0xFF1E1E1E)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { tabs.remove(tab); tab.session.close() }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                            }
                        }
                    }
                }
            }
        }

        // ── Centered Preview Overlay ─────────────────────────────────
        if (previewTab != null) {
            Popup(alignment = Alignment.Center, onDismissRequest = { previewTab = null }, properties = PopupProperties(focusable = true)) {
                var thumb by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(previewTab) { withContext(Dispatchers.IO) { thumb = ThumbnailCacheManager.getImage(context, "thumbnails", previewTab!!.url) } }

                Column(
                    modifier = Modifier.fillMaxWidth(0.85f).background(Color(0xFF1E1E1E)).border(1.dp, Color.White, RectangleShape),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(previewTab!!.title, color = Color.White, modifier = Modifier.padding(12.dp), maxLines = 1)
                    Box(
                        modifier = Modifier.fillMaxWidth().height(350.dp).background(Color.Black).clickable {
                            currentTabIndex = tabs.indexOf(previewTab!!); showTabManager = false; previewTab = null
                        }
                    ) {
                        if (thumb != null) Image(bitmap = thumb!!.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text("Tap to Enter", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                    }
                    Text("TAP TO ENTER", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }
    } else {
        // Main Browser UI
        var urlInput by remember { mutableStateOf(TextFieldValue(currentTab.url)) }
        var isUrlFocused by remember { mutableStateOf(false) }
        LaunchedEffect(currentTab.url) { if (!isUrlFocused) urlInput = TextFieldValue(currentTab.url) }

        // Centered Main Menu
        if (showMenu) {
            Popup(alignment = Alignment.Center, onDismissRequest = { showMenu = false }, properties = PopupProperties(focusable = true)) {
                Column(modifier = Modifier.width(200.dp).background(Color(0xFF1E1E1E)).border(1.dp, Color.White, RectangleShape)) {
                    ContextMenuItem("Scripting") { showMenu = false; showScripting = true }
                    ContextMenuItem("Settings") { showMenu = false; showSettings = true }
                    ContextMenuItem("Close Menu") { showMenu = false }
                }
            }
        }

        // Context Menu
        if (showContextMenu) {
            Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) {
                Column(modifier = Modifier.width(200.dp).background(Color(0xFF1E1E1E)).border(1.dp, Color.White, RectangleShape)) {
                    ContextMenuItem("Open in New Tab") {
                        val s = GeckoSession().apply { open(runtime); loadUri(contextMenuUri!!) }
                        tabs.add(TabState(s).also { setupDelegates(it) }); showContextMenu = false
                    }
                    ContextMenuItem("Copy Link") { clipboardManager.setText(AnnotatedString(contextMenuUri!!)); showContextMenu = false }
                }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        // CAPTURE BEFORE OPENING MANAGER
                        geckoViewInstance?.let { view ->
                            captureThumbnail(view, activity) { bmp ->
                                ThumbnailCacheManager.saveImage(context, "thumbnails", currentTab.url, bmp, 50)
                                showTabManager = true
                            }
                        } ?: run { showTabManager = true }
                    }) { Icon(Icons.Default.Tab, null, tint = Color.White) }

                    OutlinedTextField(
                        value = urlInput, onValueChange = { urlInput = it }, singleLine = true,
                        modifier = Modifier.weight(1f).onFocusChanged { 
                            if (it.isFocused && !isUrlFocused) urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length))
                            isUrlFocused = it.isFocused 
                        }.drawBehind { if (currentTab.progress < 100) drawRect(Color.White, size = size.copy(width = size.width * (currentTab.progress/100f))) },
                        textStyle = TextStyle(color = Color.White), shape = RectangleShape,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { currentTab.session.loadUri(if (urlInput.text.contains("://")) urlInput.text else "https://${urlInput.text}") }),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.Gray, cursorColor = Color.White)
                    )

                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
                }
                Box(modifier = Modifier.weight(1f)) { GeckoViewBox() }
            }
        }
    }

    if (showSettings) SettingsDialog(prefs, { showSettings = false })
    if (showScripting) ScriptingDialog("", { showScripting = false }, { currentTab.session.loadUri("javascript:$it") })
}

@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp))
}

@Composable
fun ScriptingDialog(init: String, onDismiss: () -> Unit, onRun: (String) -> Unit) {
    var s by remember { mutableStateOf(init) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E1E), shape = RectangleShape,
        title = { Text("JS Inject", color = Color.White) },
        text = { OutlinedTextField(s, { s = it }, modifier = Modifier.fillMaxWidth().height(150.dp), textStyle = TextStyle(color = Color.White)) },
        confirmButton = { TextButton(onClick = { onRun(s); onDismiss() }) { Text("Run", color = Color.White) } }
    )
}

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E1E), shape = RectangleShape,
        title = { Text("Settings", color = Color.White) },
        text = { Text("Minimalist Settings Panel", color = Color.Gray) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.White) } }
    )
}
