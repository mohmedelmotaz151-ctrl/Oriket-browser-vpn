package com.orkeit.browservpn

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OriketBrowserApp() {
    val context = LocalContext.current
    var address by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(BrowserSection.HOME) }
    var tabCount by remember { mutableIntStateOf(1) }
    var vpnDialog by remember { mutableStateOf(false) }
    var privateMode by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val bookmarks = remember { mutableStateListOf<Pair<String, String>>() }
    val history = remember { mutableStateListOf<Pair<String, String>>() }

    fun navigate(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        val target = when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.contains(".") && !value.contains(" ") -> "https://$value"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")
        }
        address = target
        currentUrl = target
        section = BrowserSection.HOME
        webView?.loadUrl(target)
        if (!privateMode) history.add(0, (value to target))
    }

    BackHandler(enabled = currentUrl != null && webView?.canGoBack() == true) {
        webView?.goBack()
    }

    MaterialTheme(
        colorScheme = lightColorScheme(primary = Navy, secondary = Red, background = Canvas, surface = Color.White)
    ) {
        Column(Modifier.fillMaxSize().background(Canvas)) {
            Header(
                vpnOn = false,
                privateMode = privateMode,
                tabCount = tabCount,
                onVpn = { vpnDialog = true },
                onPrivate = { privateMode = !privateMode },
                onTabs = { tabCount = (tabCount + 1).coerceAtMost(99) }
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
                            Toast.makeText(context, "تمت الإضافة للمفضلة", Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.BookmarkBorder, "إضافة للمفضلة", tint = Navy, modifier = Modifier.size(19.dp))
                    }
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.White),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (url != null) address = url
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    pageTitle = view?.title.orEmpty()
                                    if (url != null) currentUrl = url
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                }
                            }
                            webView = this
                            loadUrl(currentUrl ?: "https://www.google.com")
                        }
                    },
                    update = { webView = it }
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (section) {
                        BrowserSection.HOME -> HomeContent(
                            onSearch = { navigate(it) },
                            onQuickLink = { navigate(it) },
                            onVpn = { vpnDialog = true }
                        )
                        BrowserSection.BOOKMARKS -> SavedList("المفضلة", bookmarks, onOpen = { navigate(it) }, empty = "لم تحفظ مواقع بعد")
                        BrowserSection.HISTORY -> SavedList("سجل التصفح", history, onOpen = { navigate(it) }, empty = "لا يوجد سجل تصفح")
                        BrowserSection.DOWNLOADS -> DownloadsContent()
                    }
                }
            }

            BrowserBottomBar(
                section = section,
                onSection = { section = it; if (it != BrowserSection.HOME) { currentUrl = null } },
                onBack = { if (webView?.canGoBack() == true) webView?.goBack() },
                onForward = { if (webView?.canGoForward() == true) webView?.goForward() },
                onHome = { currentUrl = null; section = BrowserSection.HOME }
            )
        }

        if (vpnDialog) {
            AlertDialog(
                onDismissRequest = { vpnDialog = false },
                icon = { Icon(Icons.Default.Shield, null, tint = Red, modifier = Modifier.size(34.dp)) },
                title = { Text("Oriket VPN", fontWeight = FontWeight.Bold, color = Navy) },
                text = { Text("واجهة VPN قيد التصميم. الاتصال الآمن لن يتفعّل حتى يتم ربط التطبيق بخادم أو مزوّد VPN فعلي.") },
                confirmButton = { TextButton(onClick = { vpnDialog = false }) { Text("حسنًا", color = Red) } },
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
private fun SavedList(title: String, items: List<Pair<String, String>>, onOpen: (String) -> Unit, empty: String) {
    Column(Modifier.fillMaxSize().background(Canvas).padding(18.dp)) {
        Text(title, color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(14.dp))
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
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsContent() {
    Column(Modifier.fillMaxSize().background(Canvas).padding(20.dp)) {
        Text("التنزيلات", color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Download, null, tint = Muted, modifier = Modifier.size(45.dp))
                Spacer(Modifier.height(12.dp))
                Text("ستظهر الملفات التي تنزّلها هنا", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
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
