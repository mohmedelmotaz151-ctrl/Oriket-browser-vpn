package com.orkeit.browservpn

import android.annotation.SuppressLint
import android.app.Activity
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import android.os.Bundle
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import java.net.URL

private fun readVpnConfig(context: Context): String? = try {
    context.openFileInput("oriket-wireguard.conf").bufferedReader().use { it.readText() }
} catch (_: Exception) { null }

private val Navy = Color(0xFF101B35)
private val NavySoft = Color(0xFF1C2B4C)
private val Red = Color(0xFFE53945)
private val Canvas = Color(0xFFF4F6FA)
private val Ink = Color(0xFF182238)
private val Muted = Color(0xFF7B8497)
private val Line = Color(0xFFE5E9F1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 27, 53)
        window.navigationBarColor = android.graphics.Color.rgb(16, 27, 53)
        setContent { OriketBrowserApp() }
    }
}

private enum class BrowserSection { HOME, BOOKMARKS, HISTORY, DOWNLOADS }
private data class BrowserTab(val id: Int, val url: String? = null, val title: String = "علامة تبويب جديدة")
private data class BrowserDownload(val name: String, val url: String, val id: Long)

private fun readPairs(context: Context, key: String): List<Pair<String, String>> = try {
    val raw = context.getSharedPreferences("orkeit_browser", Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val title = item.optString("title")
        if (title.isBlank()) null else title to item.optString("url")
    }
} catch (_: Exception) { emptyList() }

private fun savePairs(context: Context, key: String, items: List<Pair<String, String>>) {
    val array = JSONArray()
    items.take(500).forEach { (title, url) -> array.put(JSONObject().put("title", title).put("url", url)) }
    context.getSharedPreferences("orkeit_browser", Context.MODE_PRIVATE).edit().putString(key, array.toString()).apply()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OriketBrowserApp() {
    val context = LocalContext.current
    val hostActivity = context as? Activity
    var address by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(BrowserSection.HOME) }
    var tabCount by remember { mutableIntStateOf(1) }
    var vpnDialog by remember { mutableStateOf(false) }
    var tabsDialog by remember { mutableStateOf(false) }
    var privateMode by remember { mutableStateOf(false) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var vpnConfig by remember { mutableStateOf(readVpnConfig(context)) }
    var vpnConnected by remember { mutableStateOf(false) }
    var vpnPublicIp by remember { mutableStateOf<String?>(null) }
    var vpnStatus by remember { mutableStateOf(if (vpnConfig.isNullOrBlank()) "لم يتم إعداد VPN" else "تم تحميل إعداد WireGuard") }
    var pendingVpnConnect by remember { mutableStateOf(false) }

    fun connectVpnNow() {
        val configText = vpnConfig
        if (configText.isNullOrBlank()) {
            vpnStatus = "استورد ملف إعداد WireGuard أولًا"
            vpnDialog = true
            return
        }
        val validationError = WireGuardController.validateConfig(configText)
        if (validationError != null) {
            vpnStatus = validationError
            vpnConnected = false
            return
        }
        vpnStatus = "جارٍ الاتصال..."
        WireGuardController.connect(context, configText) { connected, message ->
            Handler(Looper.getMainLooper()).post {
                vpnConnected = connected
                vpnStatus = message
                pendingVpnConnect = false
                if (connected) {
                    Thread {
                        val detectedIp = try {
                            URL("https://api.ipify.org").openConnection().apply {
                                connectTimeout = 6000
                                readTimeout = 6000
                            }.getInputStream().bufferedReader().use { it.readText().trim() }
                        } catch (_: Exception) { "" }
                        Handler(Looper.getMainLooper()).post {
                            vpnPublicIp = detectedIp.takeIf { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) }
                        }
                    }.start()
                } else {
                    vpnPublicIp = null
                }
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingVpnConnect) {
            connectVpnNow()
        } else {
            pendingVpnConnect = false
            vpnStatus = "لم يتم منح إذن VPN"
        }
    }

    val importVpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val imported = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("تعذر قراءة الملف")
                val validationError = WireGuardController.validateConfig(imported)
                if (validationError != null) {
                    vpnStatus = validationError
                    Toast.makeText(context, validationError, Toast.LENGTH_LONG).show()
                } else {
                    context.openFileOutput("oriket-wireguard.conf", Context.MODE_PRIVATE).bufferedWriter().use { it.write(imported) }
                    vpnConfig = imported
                    vpnStatus = "تم استيراد إعداد WireGuard بنجاح"
                    Toast.makeText(context, "تم استيراد الإعداد", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                vpnStatus = error.localizedMessage ?: "تعذر استيراد الملف"
                Toast.makeText(context, vpnStatus, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun togglePrivateMode() {
        val enabling = !privateMode
        android.webkit.CookieManager.getInstance().removeAllCookies {
            Handler(Looper.getMainLooper()).post {
                webView?.apply {
                    clearHistory()
                    clearCache(true)
                    clearFormData()
                }
                android.webkit.CookieManager.getInstance().flush()
                privateMode = enabling
                zoomLevel = 1f
                Toast.makeText(
                    context,
                    if (enabling) "تم تفعيل التصفح الخفي؛ لن يُحفظ سجل التصفح داخل التطبيق." else "تم إيقاف التصفح الخفي ومسح ملفات الارتباط.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun requestVpnConnection() {
        val configText = vpnConfig
        if (configText.isNullOrBlank()) {
            vpnStatus = "استورد ملف إعداد WireGuard أولًا"
            return
        }
        val validationError = WireGuardController.validateConfig(configText)
        if (validationError != null) {
            vpnStatus = validationError
            return
        }
        val consentIntent = VpnService.prepare(context)
        if (consentIntent != null) {
            pendingVpnConnect = true
            vpnPermissionLauncher.launch(consentIntent)
        } else {
            connectVpnNow()
        }
    }

    fun disconnectVpn() {
        vpnStatus = "جارٍ قطع الاتصال..."
        WireGuardController.disconnect { success, message ->
            Handler(Looper.getMainLooper()).post {
                vpnConnected = !success
                vpnStatus = message
            }
        }
    }

    // Automatically reconnect on app launch when a valid configuration was saved previously.
    LaunchedEffect(Unit) {
        if (!vpnConfig.isNullOrBlank()) {
            requestVpnConnection()
        } else {
            vpnStatus = "يلزم إعداد خادم VPN مرة واحدة"
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val bookmarks = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(readPairs(context, "bookmarks")) } }
    val history = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(readPairs(context, "history")) } }
    val downloads = remember { mutableStateListOf<BrowserDownload>() }
    val tabs = remember { mutableStateListOf(BrowserTab(1)) }
    var activeTabId by remember { mutableIntStateOf(1) }
    var nextTabId by remember { mutableIntStateOf(2) }

    fun updateActiveTab(url: String? = null, title: String? = null) {
        val index = tabs.indexOfFirst { it.id == activeTabId }
        if (index >= 0) {
            val old = tabs[index]
            tabs[index] = old.copy(url = url ?: old.url, title = title ?: old.title)
        }
    }
    fun openNewTab() {
        val id = nextTabId++
        tabs.add(BrowserTab(id))
        activeTabId = id
        tabCount = tabs.size
        currentUrl = null
        address = ""
        pageTitle = ""
        webView = null
        section = BrowserSection.HOME
        tabsDialog = false
    }
    fun navigate(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        val target = when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.contains(".") && !value.contains(" ") -> "https://$value"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")
        }
        address = target
        currentUrl = target
        pageTitle = target
        section = BrowserSection.HOME
        updateActiveTab(target, target)
        webView?.loadUrl(target)
    }

    BackHandler(enabled = currentUrl != null && webView?.canGoBack() == true) {
        webView?.goBack()
    }

    MaterialTheme(
        colorScheme = lightColorScheme(primary = Navy, secondary = Red, background = Canvas, surface = Color.White)
    ) {
        Column(Modifier.fillMaxSize().background(Canvas)) {
            Header(
                vpnOn = vpnConnected,
                privateMode = privateMode,
                tabCount = tabCount,
                onVpn = { vpnDialog = true },
                onPrivate = { togglePrivateMode() },
                onTabs = { tabsDialog = true }
            )

            AddressBar(
                value = address,
                onValueChange = { address = it },
                onGo = { navigate(address) },
                onRefresh = { webView?.reload() }
            )

            if (currentUrl != null && section == BrowserSection.HOME) {
                Row(
                    Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, null, tint = Color(0xFF27966B), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(pageTitle.ifBlank { currentUrl ?: "" }, color = Muted, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val url = currentUrl
                        if (url != null && bookmarks.none { it.second == url }) {
                            bookmarks.add(0, (pageTitle.ifBlank { url } to url))
                            savePairs(context, "bookmarks", bookmarks.toList())
                            Toast.makeText(context, "تمت الإضافة للمفضلة", Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.BookmarkBorder, "إضافة للمفضلة", tint = Navy, modifier = Modifier.size(19.dp))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text("تكبير الصفحة", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            val factor = 0.9f
                            webView?.zoomBy(factor)
                            zoomLevel = (zoomLevel * factor).coerceAtLeast(0.5f)
                        },
                        modifier = Modifier.size(34.dp)
                    ) { Icon(Icons.Default.Remove, "تصغير الصفحة", tint = Navy) }
                    Text(
                        "${(zoomLevel * 100).toInt()}%",
                        color = Navy,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            webView?.zoomBy(1f / zoomLevel)
                            zoomLevel = 1f
                        }.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                    IconButton(
                        onClick = {
                            val factor = 1.1f
                            webView?.zoomBy(factor)
                            zoomLevel = (zoomLevel * factor).coerceAtMost(3f)
                        },
                        modifier = Modifier.size(34.dp)
                    ) { Icon(Icons.Default.Add, "تكبير الصفحة", tint = Red) }
                }
                key(activeTabId, privateMode) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.White),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = !privateMode
                            settings.databaseEnabled = !privateMode
                            settings.saveFormData = !privateMode
                            settings.cacheMode = if (privateMode) android.webkit.WebSettings.LOAD_NO_CACHE else android.webkit.WebSettings.LOAD_DEFAULT
                            settings.loadsImagesAutomatically = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (url != null) {
                                        address = url
                                        currentUrl = url
                                        updateActiveTab(url, view?.title ?: url)
                                    }
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val title = view?.title?.takeIf { it.isNotBlank() } ?: url.orEmpty()
                                    pageTitle = title
                                    if (url != null) {
                                        currentUrl = url
                                        address = url
                                        updateActiveTab(url, title)
                                        if (!privateMode && url.startsWith("http")) {
                                            if (history.firstOrNull()?.second != url) history.add(0, title to url)
                                            while (history.size > 500) history.removeAt(history.lastIndex)
                                            savePairs(context, "history", history.toList())
                                        }
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                private var customVideoView: View? = null
                                private var customVideoCallback: CustomViewCallback? = null
                                private var previousSystemUiVisibility: Int = 0

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                }

                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    if (view == null || hostActivity == null) {
                                        callback?.onCustomViewHidden()
                                        return
                                    }
                                    if (customVideoView != null) {
                                        onHideCustomView()
                                        return
                                    }
                                    customVideoView = view
                                    customVideoCallback = callback
                                    val decor = hostActivity.window.decorView
                                    previousSystemUiVisibility = decor.systemUiVisibility
                                    (decor as? ViewGroup)?.addView(
                                        view,
                                        ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                    decor.systemUiVisibility = (
                                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                        )
                                }

                                override fun onHideCustomView() {
                                    val view = customVideoView ?: return
                                    (view.parent as? ViewGroup)?.removeView(view)
                                    hostActivity?.window?.decorView?.systemUiVisibility = previousSystemUiVisibility
                                    customVideoView = null
                                    customVideoCallback?.onCustomViewHidden()
                                    customVideoCallback = null
                                }
                            }
                            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                                try {
                                    val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                                        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "download" }
                                    val request = DownloadManager.Request(Uri.parse(url))
                                        .setTitle(filename)
                                        .setDescription("Oriket Browser")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                                    if (mimeType != null) request.setMimeType(mimeType)
                                    if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
                                    android.webkit.CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                                    val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                    downloads.add(0, BrowserDownload(filename, url, id))
                                    Toast.makeText(context, "بدأ تنزيل " + filename, Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "تعذر بدء التنزيل", Toast.LENGTH_SHORT).show()
                                }
                            })
                            webView = this
                            loadUrl(currentUrl ?: "https://www.google.com")
                        }
                    },
                    update = { webView = it }
                )
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (section) {
                        BrowserSection.HOME -> HomeContent(
                            onSearch = { navigate(it) },
                            onQuickLink = { navigate(it) },
                            onVpn = { vpnDialog = true }
                        )
                        BrowserSection.BOOKMARKS -> SavedList("المفضلة", bookmarks, onOpen = { navigate(it) }, empty = "لم تحفظ مواقع بعد", onDelete = { item -> bookmarks.remove(item); savePairs(context, "bookmarks", bookmarks.toList()) })
                        BrowserSection.HISTORY -> SavedList("سجل التصفح", history, onOpen = { navigate(it) }, empty = "لا يوجد سجل تصفح", onDelete = { item -> history.remove(item); savePairs(context, "history", history.toList()) }, onClear = { history.clear(); savePairs(context, "history", emptyList()) })
                        BrowserSection.DOWNLOADS -> DownloadsContent(downloads, onOpenDownloads = {
                            try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                            catch (_: Exception) { Toast.makeText(context, "افتح التنزيلات من مدير الملفات", Toast.LENGTH_SHORT).show() }
                        })
                    }
                }
            }

            BrowserBottomBar(
                section = section,
                onSection = { section = it; if (it != BrowserSection.HOME) { currentUrl = null } },
                onBack = { if (webView?.canGoBack() == true) webView?.goBack() },
                onForward = { if (webView?.canGoForward() == true) webView?.goForward() },
                onHome = { currentUrl = null; address = ""; pageTitle = ""; section = BrowserSection.HOME }
            )
        }

        if (tabsDialog) {
            AlertDialog(
                onDismissRequest = { tabsDialog = false },
                title = { Text("علامات التبويب (" + tabs.size + ")", color = Navy, fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().clickable { openNewTab() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, tint = Red)
                            Spacer(Modifier.width(10.dp))
                            Text("فتح تبويب جديد", color = Navy, fontWeight = FontWeight.SemiBold)
                        }
                        Divider()
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(tabs, key = { it.id }) { tab ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    activeTabId = tab.id
                                    currentUrl = tab.url
                                    address = tab.url.orEmpty()
                                    pageTitle = tab.title
                                    section = BrowserSection.HOME
                                    webView = null
                                    tabsDialog = false
                                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, null, tint = if (tab.id == activeTabId) Red else Muted)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(tab.title, color = Navy, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(tab.url ?: "صفحة جديدة", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = {
                                        if (tabs.size == 1) {
                                            tabs[0] = BrowserTab(tab.id)
                                            activeTabId = tab.id
                                            currentUrl = null
                                            address = ""
                                            pageTitle = ""
                                            webView = null
                                        } else {
                                            val wasActive = activeTabId == tab.id
                                            val index = tabs.indexOfFirst { it.id == tab.id }
                                            tabs.remove(tab)
                                            if (wasActive) {
                                                val next = tabs.getOrNull((index - 1).coerceAtLeast(0)) ?: tabs.first()
                                                activeTabId = next.id
                                                currentUrl = next.url
                                                address = next.url.orEmpty()
                                                pageTitle = next.title
                                                webView = null
                                            }
                                        }
                                        tabCount = tabs.size
                                    }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "إغلاق التبويب", tint = Muted) }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { tabsDialog = false }) { Text("تم", color = Red) } },
                shape = RoundedCornerShape(22.dp), containerColor = Color.White
            )
        }

        if (vpnDialog) {
            AlertDialog(
                onDismissRequest = { vpnDialog = false },
                icon = { Icon(Icons.Default.Shield, null, tint = Red, modifier = Modifier.size(34.dp)) },
                title = { Text("Oriket VPN", fontWeight = FontWeight.Bold, color = Navy) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text("الحالة: " + vpnStatus, color = if (vpnConnected) Color(0xFF16845B) else Navy, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        if (vpnConnected && vpnPublicIp != null) {
                            Text("عنوان IP العام عبر الاتصال: " + vpnPublicIp, color = Color(0xFF16845B), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text("يتصل التطبيق تلقائيًا عند التشغيل إذا سبق حفظ ملف WireGuard صالح. إنشاء عنوان IP عام يتطلب خادم VPN أو مزودًا فعليًا؛ التطبيق لا يستطيع توليد IP من دون خادم.")
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { importVpnLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.UploadFile, null, tint = Red)
                            Spacer(Modifier.width(6.dp))
                            Text(if (vpnConfig == null) "استيراد ملف إعداد WireGuard" else "استبدال ملف الإعداد", color = Red)
                        }
                        if (vpnConfig != null) Text("ملف الإعداد محفوظ داخل مساحة التطبيق الخاصة.", color = Muted, fontSize = 10.sp)
                    }
                },
                confirmButton = {
                    if (vpnConnected) {
                        TextButton(onClick = { disconnectVpn() }) { Text("قطع الاتصال", color = Red) }
                    } else {
                        TextButton(onClick = { requestVpnConnection() }, enabled = !vpnConfig.isNullOrBlank()) {
                            Text("اتصال VPN", color = if (vpnConfig.isNullOrBlank()) Muted else Red)
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { vpnDialog = false }) { Text("إغلاق", color = Navy) } },
                shape = RoundedCornerShape(24.dp),
                containerColor = Color.White
            )
        }
    }
}

@Composable
private fun Header(
    vpnOn: Boolean, privateMode: Boolean, tabCount: Int,
    onVpn: () -> Unit, onPrivate: () -> Unit, onTabs: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Navy).statusBarsPadding().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Red), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Public, null, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("ORIKET", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
            Text("BROWSER  •  VPN", color = Color(0xFFBFC9DB), fontSize = 9.sp, letterSpacing = 1.5.sp)
        }
        Surface(
            onClick = onVpn, color = NavySoft, shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF344565))
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = if (vpnOn) Color(0xFF4BD6A0) else Color(0xFFFF6872), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (vpnOn) "متصل" else "VPN", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onPrivate, modifier = Modifier.size(38.dp)) {
            Icon(if (privateMode) Icons.Default.VisibilityOff else Icons.Default.PersonOutline, "الوضع الخاص", tint = if (privateMode) Red else Color.White)
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(NavySoft).clickable { onTabs() }) {
            Icon(Icons.Default.Tab, "تبويبات", tint = Color.White, modifier = Modifier.size(19.dp))
            Text(tabCount.toString(), color = Color.White, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp))
        }
    }
}

@Composable
private fun AddressBar(value: String, onValueChange: (String) -> Unit, onGo: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(15.dp)).background(Color.White).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = Red, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("ابحث أو اكتب عنوان الموقع", fontSize = 12.sp, color = Muted, maxLines = 1) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Ink),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onGo() }),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Red
                )
            )
            IconButton(onClick = onGo, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowForward, "اذهب", tint = Navy, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRefresh, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(NavySoft)) {
            Icon(Icons.Default.Refresh, "تحديث", tint = Color.White)
        }
    }
}

@Composable
private fun HomeContent(onSearch: (String) -> Unit, onQuickLink: (String) -> Unit, onVpn: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("أهلاً بك في", color = Muted, fontSize = 14.sp)
                    Text("تصفّح بلا حدود", color = Navy, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(5.dp))
                    Text("تجربة إنترنت أنيقة من أوريكيت", color = Muted, fontSize = 12.sp)
                }
                Box(Modifier.size(74.dp).clip(RoundedCornerShape(23.dp)).background(Navy), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Public, null, tint = Color.White, modifier = Modifier.size(39.dp))
                    Box(Modifier.align(Alignment.BottomEnd).size(25.dp).clip(CircleShape).background(Red), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Bolt, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Surface(
                onClick = onVpn, color = Navy, shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF293858)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Shield, null, tint = Color(0xFFFF6B75), modifier = Modifier.size(27.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("حماية VPN", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("إعداد الاتصال الآمن", color = Color(0xFFBFC9DB), fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White)
                }
            }
            Spacer(Modifier.height(25.dp))
            Text("الوصول السريع", color = Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                QuickTile("Google", "بحث الويب", Icons.Default.Search, Color(0xFF4285F4), Modifier.weight(1f)) { onQuickLink("https://www.google.com") }
                QuickTile("YouTube", "فيديوهات", Icons.Default.PlayCircle, Red, Modifier.weight(1f)) { onQuickLink("https://m.youtube.com") }
                QuickTile("Wikipedia", "المعرفة", Icons.Default.MenuBook, Color(0xFF4F647E), Modifier.weight(1f)) { onQuickLink("https://ar.wikipedia.org") }
            }
            Spacer(Modifier.height(25.dp))
            Text("مصمم ليمنحك تحكماً أكبر", color = Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            FeatureRow(Icons.Default.Speed, "تصفح سريع", "واجهة خفيفة وسهلة الاستخدام")
            FeatureRow(Icons.Default.BookmarkBorder, "مواقعك المفضلة", "احفظ المواقع المهمة للوصول إليها")
            FeatureRow(Icons.Default.VisibilityOff, "وضع خاص", "تحكم في سجل التصفح على هذا الجهاز")
            Spacer(Modifier.height(12.dp))
            Text("ملاحظة: ميزة VPN ستظهر كمتاحة بعد ربط مزوّد اتصال فعلي.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun QuickTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(17.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(vertical = 15.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(43.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(9.dp))
            Text(title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subtitle, color = Muted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Red, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SavedList(title: String, items: List<Pair<String, String>>, onOpen: (String) -> Unit, empty: String, onDelete: (Pair<String, String>) -> Unit = {}, onClear: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(Canvas).padding(18.dp)) {
        Text(title, color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(14.dp))
        if (onClear != null && items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClear) { Text("مسح السجل", color = Red) }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BookmarkBorder, null, tint = Muted, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(empty, color = Muted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { item ->
                    Surface(onClick = { onOpen(item.second) }, color = Color.White, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, null, tint = Red)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.first, color = Navy, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.second, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onDelete(item) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.DeleteOutline, "حذف", tint = Muted, modifier = Modifier.size(18.dp))
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsContent(downloads: List<BrowserDownload>, onOpenDownloads: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Canvas).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("التنزيلات", color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenDownloads) { Text("مدير التنزيلات", color = Red) }
        }
        Spacer(Modifier.height(12.dp))
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, tint = Muted, modifier = Modifier.size(45.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("لا توجد تنزيلات في هذه الجلسة", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("التنزيلات تظهر أيضًا في مدير تنزيلات Android", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(downloads, key = { it.id }) { item ->
                    Surface(color = Color.White, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.InsertDriveFile, null, tint = Red)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Navy, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("أُرسل إلى مدير تنزيلات Android", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserBottomBar(
    section: BrowserSection, onSection: (BrowserSection) -> Unit,
    onBack: () -> Unit, onForward: () -> Unit, onHome: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomAction(Icons.Default.ArrowBack, "رجوع", false, onBack)
        BottomAction(Icons.Default.ArrowForward, "تقدم", false, onForward)
        BottomAction(Icons.Default.Home, "الرئيسية", section == BrowserSection.HOME, onHome)
        BottomAction(Icons.Default.BookmarkBorder, "المفضلة", section == BrowserSection.BOOKMARKS) { onSection(BrowserSection.BOOKMARKS) }
        BottomAction(Icons.Default.History, "السجل", section == BrowserSection.HISTORY) { onSection(BrowserSection.HISTORY) }
        BottomAction(Icons.Default.Download, "التنزيلات", section == BrowserSection.DOWNLOADS) { onSection(BrowserSection.DOWNLOADS) }
    }
}

@Composable
private fun BottomAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Red else Muted, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (selected) Red else Muted, fontSize = 8.sp, maxLines = 1)
    }
}
