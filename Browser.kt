package com.grey.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreyBrowser()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyBrowser() {
    val context = LocalContext.current
    val runtime = remember { GeckoRuntime.create(context) }

    // Tabs
    val tabs = remember {
        mutableStateListOf<GeckoSession>().apply {
            val initialSession = GeckoSession().apply {
                open(runtime)
                loadUri("about:blank")
            }
            add(initialSession)
        }
    }
    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabManager by remember { mutableStateOf(false) }

    // Menu state
    var showMenu by remember { mutableStateOf(false) }

    // Settings dialog state
    var showSettings by remember { mutableStateOf(false) }

    // GeckoView for the current tab
    @Composable
    fun GeckoViewBox() {
        AndroidView(
            factory = { ctx ->
                GeckoView(ctx).apply {
                    setSession(tabs[currentTabIndex])
                }
            },
            update = { geckoView ->
                geckoView.setSession(tabs[currentTabIndex])
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    // ── Settings Dialog ───────────────────────────────────────
    if (showSettings) {
        SettingsDialog(
            runtime = runtime,
            onDismiss = { showSettings = false }
        )
    }

    // ── Tab Manager Sheet ─────────────────────────────────────
    if (showTabManager) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTabManager = false },
            sheetState = sheetState,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White,
            shape = RectangleShape
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showTabManager = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Text("Tabs", style = TextStyle(color = Color.White, fontSize = 18.sp))
                }

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(tabs) { index, _ ->
                        val isCurrent = index == currentTabIndex
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentTabIndex = index
                                    showTabManager = false
                                },
                            color = if (isCurrent) Color(0xFF2A2A2A) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Tab ${index + 1}",
                                    color = if (isCurrent) Color.White else Color.Gray,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    if (tabs.size > 1) {
                                        tabs.removeAt(index).close()
                                        if (currentTabIndex >= tabs.size)
                                            currentTabIndex = tabs.lastIndex
                                    }
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close tab", tint = Color.White)
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val s = GeckoSession()
                        s.open(runtime)
                        s.loadUri("about:blank")
                        tabs.add(s)
                        currentTabIndex = tabs.lastIndex
                        showTabManager = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(2.dp, Color.White)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Tab", color = Color.White)
                }
            }
        }
    }

    // URL bar state
    var urlInput by remember { mutableStateOf("about:blank") }
    LaunchedEffect(currentTabIndex) {
        urlInput = "about:blank"   // reset for now
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top row: Tab button | URL bar | Menu button ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tab button (left)
                IconButton(onClick = { showTabManager = true }) {
                    Icon(Icons.Default.Tab, contentDescription = "Open tabs", tint = Color.White)
                }

                // URL bar
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = {
                        Text("Enter URL", color = Color.White.copy(alpha = 0.5f))
                    },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    shape = RectangleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val uri = if (urlInput.contains("://")) urlInput
                                      else "https://$urlInput"
                            tabs[currentTabIndex].loadUri(uri)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                        cursorColor = Color.White,
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E)
                    )
                )

                // Menu button (right) with dropdown
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = Color(0xFF1E1E1E),
                        shape = RectangleShape
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings", color = Color.White) },
                            onClick = {
                                showMenu = false
                                showSettings = true
                            }
                        )
                        // Future items can be added here
                    }
                }
            }

            // Web view area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GeckoViewBox()
            }
        }
    }
}

// ── Settings Dialog ───────────────────────────────────────────────
@Composable
fun SettingsDialog(runtime: GeckoRuntime, onDismiss: () -> Unit) {
    // This memory holds all current preference values so toggles can be read/written instantly.
    // Using mutableStateMapOf with keys as pref names.
    val prefs = remember { runtime.getPrefs() }
    var jsEnabled by remember { mutableStateOf(prefs.getBoolean("javascript.enabled", true)) }
    var trackingProtection by remember { mutableStateOf(prefs.getBoolean("privacy.trackingprotection.enabled", false)) }
    var cookieBehavior by remember { mutableStateOf(prefs.getInt("network.cookie.cookieBehavior", 0)) }
    var autoplayMedia by remember { mutableStateOf(prefs.getBoolean("media.autoplay.enabled", true)) }

    // On dismiss, apply any changed values
    val applyChanges = {
        prefs.setBoolean("javascript.enabled", jsEnabled)
        prefs.setBoolean("privacy.trackingprotection.enabled", trackingProtection)
        prefs.setInt("network.cookie.cookieBehavior", cookieBehavior)
        prefs.setBoolean("media.autoplay.enabled", autoplayMedia)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Settings", color = Color.White, fontSize = 20.sp)
        },
        text = {
            Column {
                SettingSwitch(
                    label = "JavaScript",
                    checked = jsEnabled,
                    onCheckedChange = { jsEnabled = it }
                )
                SettingSwitch(
                    label = "Tracking Protection",
                    checked = trackingProtection,
                    onCheckedChange = { trackingProtection = it }
                )
                // Cookies as a list of options
                Text("Cookie Behavior", color = Color.White, fontSize = 14.sp)
                CookieBehaviorSelector(
                    current = cookieBehavior,
                    onChange = { cookieBehavior = it }
                )
                SettingSwitch(
                    label = "Autoplay Media",
                    checked = autoplayMedia,
                    onCheckedChange = { autoplayMedia = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = applyChanges) {
                Text("OK", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        },
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White,
        shape = RectangleShape,
        tonalElevation = 0.dp
    )
}

// Helper composable for a switch row
@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF444444),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF444444)
            )
        )
    }
}

// Cookie behavior selector (dropdown list of three options)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieBehaviorSelector(current: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "Accept all" to 0,
        "Reject all" to 1,
        "Only from visited" to 2
    )
    val selectedText = options.firstOrNull { it.second == current }?.first ?: "Accept all"

    Box {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            trailingIcon = {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White) // placeholder icon; basically just to have a dropdown indicator
                // You could replace with a drop‑down arrow icon
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            shape = RectangleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.White,
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E),
                cursorColor = Color.White
            ),
            enabled = true
        )
        // Invisible click overlay to open the dropdown
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color(0xFF1E1E1E),
            shape = RectangleShape
        ) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label, color = Color.White) },
                    onClick = {
                        onChange(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
