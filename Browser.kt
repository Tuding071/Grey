package com.grey.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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

    // Tab state
    val tabs = remember { mutableStateListOf<GeckoSession>() }
    var currentTabIndex by remember { mutableIntStateOf(0) }
    var showTabManager by remember { mutableStateOf(false) }

    // Helper: ensure at least one tab exists
    fun ensureAtLeastOneTab() {
        if (tabs.isEmpty()) {
            val s = GeckoSession().also { it.open(runtime) }
            tabs.add(s)
            currentTabIndex = 0
            s.loadUri("about:blank")
        }
    }

    // Initialise with one tab
    LaunchedEffect(Unit) {
        ensureAtLeastOneTab()
    }

    // GeckoView that automatically updates its session when current tab changes
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

    // Tab manager sheet
    if (showTabManager) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        
        ModalBottomSheet(
            onDismissRequest = { showTabManager = false },
            sheetState = sheetState,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White,
            shape = RectangleShape    // square edges – matches your style
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header with exit button (top left)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showTabManager = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close tab manager", tint = Color.White)
                    }
                    Text("Tabs", style = TextStyle(color = Color.White, fontSize = 18.sp))
                }

                // Tab list – oldest first, newest at the bottom
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
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
                                // Tab title (just a placeholder for now)
                                Text(
                                    text = "Tab ${index + 1}",
                                    color = if (isCurrent) Color.White else Color.Gray,
                                    modifier = Modifier.weight(1f)
                                )
                                // Close button
                                IconButton(onClick = {
                                    // Close the tab
                                    if (tabs.size > 1) {
                                        tabs.removeAt(index).close()   // close GeckoSession
                                        if (currentTabIndex >= tabs.size) {
                                            currentTabIndex = tabs.lastIndex
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close tab", tint = Color.White)
                                }
                            }
                        }
                    }
                }

                // Bottom: new tab button
                OutlinedButton(
                    onClick = {
                        val s = GeckoSession()
                        s.open(runtime)
                        s.loadUri("about:blank")
                        tabs.add(s)
                        currentTabIndex = tabs.lastIndex
                        showTabManager = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
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
    // Sync urlInput when current tab changes (optional but nice)
    LaunchedEffect(currentTabIndex) {
        urlInput = "about:blank"   // reset to blank, or you can store URLs per tab
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top row: tab button + URL bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tab button on the left
                IconButton(onClick = { showTabManager = true }) {
                    Icon(Icons.Default.Tab, contentDescription = "Open tabs", tint = Color.White)
                }

                // URL bar (takes remaining space)
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = {
                        Text("Enter URL", color = Color.White.copy(alpha = 0.5f))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
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
            }

            // Web content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                GeckoViewBox()
            }
        }
    }
}
