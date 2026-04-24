package com.grey.browser

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
        setContent {
            GreyBrowser()
        }
    }
}

// ── Cache Manager for Thumbnails & Favicons ─────────────────────────
object ThumbnailCacheManager {
    fun saveImage(context: Context, folderName: String, fileName: String, bitmap: Bitmap, limit: Int) {
        val dir = File(context.filesDir, folderName)
        if (!dir.exists()) dir.mkdirs()

        val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val file = File(dir, "$safeFileName.png")
        
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, out)
            }
            
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size > limit) {
                val toDeleteCount = files.size - limit
                for (i in 0 until toDeleteCount) {
                    files[i].delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getImage(context: Context, folderName: String, fileName: String): Bitmap? {
        val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val file = File(context.filesDir, "$folderName/$safeFileName.png")
        if (file.exists()) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        return null
    }
}

class TabState(val session: GeckoSession) {
    var title by mutableStateOf("New Tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
    var lastUpdated by mutableLongStateOf(System.currentTimeMillis())
}

fun getDomainName(url: String): String {
    if (url == "about:blank" || url.isBlank()) return "Home"
    return try {
        Uri.parse(url).host?.removePrefix("www.") ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val clipboardManager = LocalClipboardManager.current
    val runtime = remember { GeckoRuntime.create(context) }
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()

    val applySettingsToSession = { session: GeckoSession ->
        session.settings.allowJavascript = prefs.getBoolean("javascript.enabled", true)
        session.settings.useTrackingProtection = prefs.getBoolean("privacy.trackingprotection.enabled", false)
        session.settings.suspendMediaWhenInactive = !prefs.getBoolean("media.autoplay.enabled", true)
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuUri by remember { mutableStateOf<String?>(null) }

    val setupDelegates = { tabState: TabState ->
        tabState.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tabState.url = url
                tabState.progress = 5
                tabState.lastUpdated = System.currentTimeMillis()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tabState.progress = 100
                tabState.lastUpdated = System.currentTimeMillis()
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tabState.progress = progress
            }
        }
        
        tabState.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, 
                url: String?, 
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, 
                hasUserGesture: Boolean
            ) {
                if (url != null) {
                    tabState.url = url
                    tabState.lastUpdated = System.currentTimeMillis()
                }
            }
        }

        tabState.session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tabState.title = title ?: "New Tab"
            }
            override fun onContextMenu(
                session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement
            ) {
                if (element.linkUri != null) {
                    contextMenuUri = element.linkUri
                    showContextMenu = true
                }
            }
        }
    }

    val tabs = remember {
        mutableStateListOf<TabState>().apply {
            val initialSession = GeckoSession().apply {
                applySettingsToSession(this)
                open(runtime)
                loadUri("about:blank")
            }
            val initialTab = TabState(initialSession)
            setupDelegates(initialTab)
            add(initialTab)
        }
    }

    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabManager by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showScripting by remember { mutableStateOf(false) }

    val pinnedDomains = remember { mutableStateListOf<String>() }
    var selectedDomain by remember { mutableStateOf("Home") }

    val currentTab = tabs.getOrNull(currentTabIndex) ?: tabs.first()

    BackHandler {
        if (showTabManager) {
            showTabManager = false
        } else if (currentTab.url == "about:blank") {
            if (tabs.size > 1) {
                tabs.remove(currentTab)
                currentTab.session.close()
                currentTabIndex = minOf(currentTabIndex, tabs.lastIndex)
            } else {
                activity?.finish()
            }
        } else {
            currentTab.session.goBack()
        }
    }

    @Composable
    fun GeckoViewBox() {
        AndroidView(
            factory = { ctx -> GeckoView(ctx).apply { setSession(currentTab.session) } },
            update = { geckoView -> geckoView.setSession(currentTab.session) },
            modifier = Modifier.fillMaxSize()
        )
    }

    if (showTabManager) {
        val domainGroups = tabs.groupBy { getDomainName(it.url) }
        val sortedDomains = domainGroups.keys.sortedWith(
            compareByDescending<String> { pinnedDomains.contains(it) }
            .thenByDescending { domain -> domainGroups[domain]?.maxOfOrNull { it.lastUpdated } ?: 0L }
        )

        LaunchedEffect(Unit) {
            selectedDomain = getDomainName(currentTab.url)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(16.dp), 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showTabManager = false }) { 
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) 
                    }
                    Text("Tabs", style = TextStyle(color = Color.White, fontSize = 20.sp))
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    items(sortedDomains) { domain ->
                        val isSelected = domain == selectedDomain
                        val isPinned = pinnedDomains.contains(domain)
                        val tabCount = domainGroups[domain]?.size ?: 0
                        
                        var faviconBitmap by remember { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(domain) {
                            withContext(Dispatchers.IO) {
                                faviconBitmap = ThumbnailCacheManager.getImage(context, "favicons", domain)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable { selectedDomain = domain }
                                .border(if (isSelected) 1.dp else 0.dp, Color.White, RectangleShape),
                            color = if (isSelected) Color(0xFF2A2A2A) else Color.Transparent
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isPinned) Text("📌 ", fontSize = 12.sp)
                                
                                if (faviconBitmap != null) {
                                    Image(bitmap = faviconBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Text(domain, color = Color.White, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                
                                Box(modifier = Modifier.background(Color.Gray).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                    Text(tabCount.toString(), color = Color.White, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                Divider(color = Color.DarkGray, thickness = 1.dp)

                val tabsToShow = domainGroups[selectedDomain] ?: emptyList()
                val listState = rememberLazyListState()
                
                LaunchedEffect(selectedDomain) {
                    val index = tabsToShow.indexOf(currentTab)
                    if (index >= 0) listState.scrollToItem(index)
                }

                LazyColumn(
                    state = listState, 
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(tabsToShow) { tab ->
                        val isCurrent = tab == currentTab
                        var thumbBitmap by remember { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(tab.url) {
                            withContext(Dispatchers.IO) {
                                thumbBitmap = ThumbnailCacheManager.getImage(context, "thumbnails", tab.url)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentTabIndex = tabs.indexOf(tab)
                                    showTabManager = false
                                }
                                .border(if (isCurrent) 2.dp else 1.dp, if (isCurrent) Color.White else Color.DarkGray, RectangleShape),
                            color = Color(0xFF1E1E1E)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = tab.title, color = if (isCurrent) Color.White else Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        tab.session.close()
                                        tabs.remove(tab)
                                        if (tabs.isEmpty()) {
                                            val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri("about:blank") }
                                            tabs.add(TabState(s).also { setupDelegates(it) })
                                            currentTabIndex = 0
                                        } else {
                                            if (currentTabIndex >= tabs.size) currentTabIndex = tabs.lastIndex
                                        }
                                        selectedDomain = getDomainName(tabs[currentTabIndex].url)
                                    }) { Icon(Icons.Default.Close, contentDescription = "Close tab", tint = Color.White) }
                                }
                                Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                                    if (thumbBitmap != null) {
                                        Image(bitmap = thumbBitmap!!.asImageBitmap(), contentDescription = "Preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Text("No Preview", color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(16.dp)) {
                    OutlinedButton(
                        onClick = {
                            val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri("about:blank") }
                            tabs.add(TabState(s).also { setupDelegates(it) })
                            currentTabIndex = tabs.lastIndex
                            selectedDomain = "Home"; showTabManager = false
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Tab")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        val isPinned = pinnedDomains.contains(selectedDomain)
                        OutlinedButton(onClick = { if (isPinned) pinnedDomains.remove(selectedDomain) else pinnedDomains.add(selectedDomain) }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) {
                            Text(if (isPinned) "Unpin Group" else "Pin Group")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val tabsToRemove = tabs.filter { getDomainName(it.url) == selectedDomain }
                            tabsToRemove.forEach { it.session.close() }
                            tabs.removeAll(tabsToRemove)
                            if (tabs.isEmpty()) {
                                val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri("about:blank") }
                                tabs.add(TabState(s).also { setupDelegates(it) })
                                currentTabIndex = 0
                            } else { currentTabIndex = minOf(currentTabIndex, tabs.lastIndex) }
                            selectedDomain = getDomainName(tabs[currentTabIndex].url)
                        }, modifier = Modifier.weight(1f), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) {
                            Text("Delete Group")
                        }
                    }
                }
            }
        }
    } else {
        // Dialogs & Main UI when tab manager is closed
        if (showSettings) SettingsDialog(prefs = prefs, onDismiss = { showSettings = false }, onSettingsApplied = { tabs.forEach { applySettingsToSession(it.session) } })
        if (showScripting) ScriptingDialog(initialScript = prefs.getString("user_script", "alert('Hello!');") ?: "", onDismiss = { showScripting = false }, onSaveAndRun = { script -> prefs.edit().putString("user_script", script).apply(); currentTab.session.loadUri("javascript:" + Uri.encode("(function(){\n$script\n})();")); showScripting = false })
        if (showContextMenu && contextMenuUri != null) {
            Popup(alignment = Alignment.Center, onDismissRequest = { showContextMenu = false }, properties = PopupProperties(focusable = true)) {
                Column(modifier = Modifier.border(1.dp, Color.White, RectangleShape).background(Color(0xFF1E1E1E)).width(IntrinsicSize.Max)) {
                    ContextMenuItem("New Tab") { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(contextMenuUri!!) }; tabs.add(TabState(s).also { setupDelegates(it) }); currentTabIndex = tabs.lastIndex; showContextMenu = false }
                    ContextMenuItem("Open in Background") { val s = GeckoSession().apply { applySettingsToSession(this); open(runtime); loadUri(contextMenuUri!!) }; tabs.add(TabState(s).also { setupDelegates(it) }); showContextMenu = false }
                    ContextMenuItem("Copy link") { clipboardManager.setText(AnnotatedString(contextMenuUri!!)); showContextMenu = false }
                }
            }
        }

        var urlInput by remember { mutableStateOf(TextFieldValue(currentTab.url)) }
        var isUrlFocused by remember { mutableStateOf(false) }

        LaunchedEffect(currentTab.url) { if (!isUrlFocused) { urlInput = TextFieldValue(currentTab.url) } }

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showTabManager = true }) { Icon(Icons.Default.Tab, contentDescription = "Tabs", tint = Color.White) }
                    val isLoading = currentTab.progress in 1..99
                    OutlinedTextField(
                        value = urlInput, onValueChange = { urlInput = it }, singleLine = true,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp).background(Color(0xFF1E1E1E))
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused && !isUrlFocused) {
                                    urlInput = urlInput.copy(selection = TextRange(0, urlInput.text.length))
                                }
                                isUrlFocused = focusState.isFocused
                            }.drawBehind { if (isLoading) { drawRect(color = Color.White, size = size.copy(width = size.width * (currentTab.progress / 100f))) } },
                        textStyle = TextStyle(color = if (isLoading) Color.Gray else Color.White, fontSize = 16.sp),
                        shape = RectangleShape, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { val uri = if (urlInput.text.contains("://")) urlInput.text else "https://${urlInput.text}"; currentTab.session.loadUri(uri) }),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White, cursorColor = Color.White)
                    )
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E)) {
                        DropdownMenuItem(text = { Text("Scripting", color = Color.White) }, onClick = { showMenu = false; showScripting = true })
                        DropdownMenuItem(text = { Text("Settings", color = Color.White) }, onClick = { showMenu = false; showSettings = true })
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { GeckoViewBox() }
            }
        }
    }
}

@Composable
fun ContextMenuItem(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) { Text(text, color = Color.White, fontSize = 16.sp) }
}

@Composable
fun ScriptingDialog(initialScript: String, onDismiss: () -> Unit, onSaveAndRun: (String) -> Unit) {
    var script by remember { mutableStateOf(initialScript) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Inject JavaScript") }, text = { Column { OutlinedTextField(value = script, onValueChange = { script = it }, modifier = Modifier.fillMaxWidth().height(200.dp), textStyle = TextStyle(color = Color.White), shape = RectangleShape) } }, confirmButton = { TextButton(onClick = { onSaveAndRun(script) }) { Text("Run", color = Color.White) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.White) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, shape = RectangleShape)
}

@Composable
fun SettingsDialog(prefs: SharedPreferences, onDismiss: () -> Unit, onSettingsApplied: () -> Unit) {
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var trackingProtection by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var autoplayMedia by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Settings") }, text = { Column { SettingSwitch("JavaScript", jsEnabled) { jsEnabled = it }; SettingSwitch("Tracking Protection", trackingProtection) { trackingProtection = it }; SettingSwitch("Autoplay Media", autoplayMedia) { autoplayMedia = it } } }, confirmButton = { TextButton(onClick = { prefs.edit().putBoolean("javascript.enabled", jsEnabled).putBoolean("privacy.trackingprotection.enabled", trackingProtection).putBoolean("media.autoplay.enabled", autoplayMedia).apply(); onSettingsApplied(); onDismiss() }) { Text("OK", color = Color.White) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } }, containerColor = Color(0xFF1E1E1E), titleContentColor = Color.White, shape = RectangleShape)
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, color = Color.White, fontSize = 14.sp); Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("Accept all" to 0, "Reject all" to 1, "Only from visited" to 2)
    val selectedText = options.firstOrNull { it.second == current }?.first ?: "Accept all"
    Box {
        OutlinedTextField(value = selectedText, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White), shape = RectangleShape)
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.border(1.dp, Color.White, RectangleShape), containerColor = Color(0xFF1E1E1E)) {
            options.forEach { (label, value) -> DropdownMenuItem(text = { Text(label, color = Color.White) }, onClick = { onChange(value); expanded = false }) }
        }
    }
}
