package dev.bootloaderstudio.app.ui.webview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.ui.theme.Orange
import kotlinx.coroutines.delay
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * 100% Jetpack Compose Reusable WebView Screen.
 *
 * Configured identically to the reference MiTools implementation:
 * - Natural WebView lifecycle and rendering without aggressive DOM/visibility manipulation.
 * - Mobile User-Agent "(Android) Mobile" triggering Xiaomi's active mobile SPA chunk.
 * - Extracts passToken, deviceId, and userId across redirects.
 * - Completion strictly synchronized with Xiaomi's STS authorization endPattern ({"R":"","S":"OK"}).
 * - Instantly displays a friendly "Login Successful" confirmation dialog overlay when completion is detected,
 *   masking raw JSON completely before smoothly transitioning to the account screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppWebViewScreen(
    initialUrl: String,
    title: String,
    subtitle: String? = null,
    userAgent: String? = null,
    endPattern: String? = null,
    onBack: () -> Unit,
    onCookiesExtracted: ((Map<String, String>) -> Boolean)? = null,
    onSuccess: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentUrl by remember { mutableStateOf(initialUrl) }
    var currentTitle by remember { mutableStateOf(title) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    var passToken by remember { mutableStateOf<String?>(null) }
    var deviceId by remember { mutableStateOf<String?>(null) }
    var userId by remember { mutableStateOf<String?>(null) }
    var monitoringEnded by remember { mutableStateOf(false) }
    var checkingCompletion by remember { mutableStateOf(false) }
    var completionError by remember { mutableStateOf(false) }

    val webViewRef = remember { AtomicReference<WebView?>(null) }

    // Intercept back presses: go back in WebView history if possible, else pop back
    BackHandler(enabled = true) {
        val wv = webViewRef.get()
        if (wv?.canGoBack() == true) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    fun extractCookies() {
        val cookieManager = CookieManager.getInstance()
        val accountCookies = cookieManager.getCookie("https://account.xiaomi.com") ?: ""
        val unlockCookies = cookieManager.getCookie("https://unlock.update.miui.com") ?: ""
        val combined = "$accountCookies; $unlockCookies"

        fun extractValue(key: String): String? {
            return combined.split(";")
                .map { it.trim() }
                .find { it.startsWith("$key=") }
                ?.substringAfter("$key=")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
        }

        passToken = passToken ?: extractValue("passToken")
        deviceId = deviceId ?: extractValue("deviceId")
        userId = userId ?: extractValue("userId")
    }

    fun checkForEndSignal(view: WebView) {
        if (monitoringEnded || checkingCompletion || completionError || endPattern.isNullOrBlank()) return

        checkingCompletion = true
        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
            try {
                if (html != null && !monitoringEnded && !completionError) {
                    val cleanedHtml = html.replace("\\u003C", "<")
                        .replace("\\u003E", ">")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")

                    if (cleanedHtml.contains(endPattern)) {
                        extractCookies()
                        val hasAll = !passToken.isNullOrBlank() &&
                                     !deviceId.isNullOrBlank() &&
                                     !userId.isNullOrBlank()

                        if (hasAll) {
                            val cookies = mapOf(
                                "passToken" to passToken!!,
                                "deviceId" to deviceId!!,
                                "userId" to userId!!
                            )
                            // Only show the success overlay after the session
                            // has actually been accepted and persisted.
                            val accepted = runCatching {
                                onCookiesExtracted?.invoke(cookies) ?: true
                            }.getOrDefault(false)

                            if (accepted) {
                                monitoringEnded = true
                            } else {
                                completionError = true
                            }
                        } else {
                            view.loadUrl(initialUrl)
                        }
                    }
                }
            } finally {
                checkingCompletion = false
            }
        }
    }

    // Safety timeout: dismiss loading progress after 5s if still active
    LaunchedEffect(reloadTrigger, currentUrl) {
        delay(5000)
        if (isLoading) {
            isLoading = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = currentTitle.ifBlank { title },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val displaySubtitle = subtitle ?: runCatching {
                                URI(currentUrl).host
                            }.getOrNull() ?: currentUrl
                            Text(
                                text = displaySubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.webview_nav_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            errorMessage = null
                            isLoading = true
                            progress = 0f
                            webViewRef.get()?.reload()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.webview_nav_reload)
                            )
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.webview_nav_menu)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.webview_menu_reload)) },
                                onClick = {
                                    showMenu = false
                                    errorMessage = null
                                    isLoading = true
                                    progress = 0f
                                    webViewRef.get()?.reload()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.webview_menu_open_external)) },
                                onClick = {
                                    showMenu = false
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                        context.startActivity(intent)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.webview_menu_copy_link)) },
                                onClick = {
                                    showMenu = false
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", currentUrl))
                                    Toast.makeText(context, context.getString(R.string.webview_toast_link_copied), Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.webview_menu_clear_cache)) },
                                onClick = {
                                    showMenu = false
                                    CookieManager.getInstance().removeAllCookies {
                                        CookieManager.getInstance().flush()
                                        WebStorage.getInstance().deleteAllData()
                                        webViewRef.get()?.clearCache(true)
                                        passToken = null
                                        deviceId = null
                                        userId = null
                                        monitoringEnded = false
                                        checkingCompletion = false
                                        completionError = false
                                        Toast.makeText(context, context.getString(R.string.webview_toast_cache_cleared), Toast.LENGTH_SHORT).show()
                                        reloadTrigger++
                                    }
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val error = errorMessage
            if (error != null) {
                // Material 3 Error State View
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = stringResource(R.string.webview_error_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.webview_error_open_browser))
                        }
                        Button(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                progress = 0f
                                webViewRef.get()?.reload()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.webview_error_retry))
                        }
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                setSupportZoom(false)
                                builtInZoomControls = false
                                displayZoomControls = false
                                cacheMode = WebSettings.LOAD_DEFAULT

                                if (!userAgent.isNullOrBlank()) {
                                    userAgentString = userAgent
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                    if (newProgress >= 100) {
                                        isLoading = false
                                    }
                                }

                                override fun onReceivedTitle(view: WebView?, webTitle: String?) {
                                    if (!webTitle.isNullOrBlank() && !webTitle.startsWith("http")) {
                                        currentTitle = webTitle
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (!monitoringEnded) {
                                        extractCookies()
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    currentUrl = url
                                    isLoading = true
                                    errorMessage = null
                                    if (!monitoringEnded) {
                                        extractCookies()
                                    }
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    currentUrl = url
                                    isLoading = false
                                    if (!monitoringEnded) {
                                        extractCookies()
                                        checkForEndSignal(view)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError
                                ) {
                                    if (request.isForMainFrame) {
                                        val desc = error.description?.toString().orEmpty()
                                        if (!desc.contains("ERR_ABORTED", ignoreCase = true)) {
                                            isLoading = false
                                            errorMessage = context.getString(
                                                R.string.webview_error_format,
                                                desc,
                                                error.errorCode
                                            )
                                        }
                                    }
                                }
                            }

                            loadUrl(initialUrl)
                            webViewRef.set(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Dialog and card overlay upon successful login: completely masks raw {"R":"","S":"OK"} JSON
                if (monitoringEnded) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(64.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Orange,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                    }

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.login_success_title),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.login_success_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp),
                                        color = Orange
                                    )

                                    Button(
                                        onClick = { onSuccess?.invoke() },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(stringResource(R.string.login_success_continue))
                                    }
                                }
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        delay(1200)
                        onSuccess?.invoke()
                    }
                }

                if (completionError) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.login_save_failed_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = stringResource(R.string.login_save_failed_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        textAlign = TextAlign.Center
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = onBack,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.btn_back))
                                        }
                                        Button(
                                            onClick = {
                                                completionError = false
                                                webViewRef.get()?.let { checkForEndSignal(it) }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.login_save_failed_retry))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating progress indicator on top edge: constant bounds, zero layout shift
            if (isLoading && progress < 1f && !monitoringEnded) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter),
                    color = Orange,
                    trackColor = Color.Transparent
                )
            }
        }
    }

    // Keyed solely on lifecycleOwner so the WebView is not prematurely destroyed during state updates
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webViewRef.get()?.onResume()
                Lifecycle.Event.ON_PAUSE -> webViewRef.get()?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewRef.getAndSet(null)?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
        }
    }
}
