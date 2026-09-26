package com.xinjiyuan.oridesk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xinjiyuan.oridesk.databinding.ActivityMainBinding

/**
 * 主界面：WebView 壳（决策 1）。
 *
 * "手机上要能处理工单"这件事几乎免费获得 —— 网页已经做了窄屏适配
 * （移动顶栏 / 底部 Tab / 抽屉 / 卡片流），WebView 直接复用，不必重写一套原生 UI。
 * 本 Activity 只负责四件事：加载页面、处理深链、申请通知权限、在配置缺失时给引导。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var webView: WebView

    /** 记录当前已加载的服务器地址，用于检测"用户刚在设置里改了地址"。 */
    private var loadedServer: String? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不影响主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        // Android 15 强制 edge-to-edge，不处理的话网页顶栏会被状态栏压住（见 UiInsets）。
        UiInsets.apply(this, binding.root)
        setupWebView()
        setupBackHandling()

        binding.setupButton.setOnClickListener { openSettings() }

        askNotificationPermission()
        openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 从通知点进来时走这里（launchMode=singleTask，不会重建 Activity，
        // 所以 WebView 的滚动位置与会话都保留着）。
        openFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshSetupBar()
        val server = prefs.serverUrl
        // 用户可能刚在设置页改了服务器地址：此时必须重新加载，
        // 否则界面还是旧服务器的页面，而后续 API 调用已经指向新服务器。
        if (server.isNotEmpty() && loadedServer != null && server != loadedServer) {
            loadUrl("$server/")
        }
    }

    // ---------------------------------------------------------------- WebView
    private fun setupWebView() {
        webView = binding.webView
        webView.settings.apply {
            javaScriptEnabled = true // 网页依赖 HTMX 与富文本编辑器
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 本应用不需要读取本地文件，关掉以缩小攻击面
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString OriDeskAndroid"
        }

        CookieManager.getInstance().apply {
            // 会话 cookie 必须接受，且与 ApiClient 共用同一个 cookie 罐
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                // 深链在 WebView 内部出现时也拦下来（例如网页里放了 oridesk:// 链接）
                val ticketId = UrlRules.parseTicketId(uri)
                if (ticketId != null) {
                    openTicket(ticketId)
                    return true
                }
                // 其余一律留在 WebView 内，不要甩给外部浏览器 ——
                // 甩出去会用另一套会话，用户得重新登录一次。
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                refreshSetupBar()
            }
        }
    }

    private fun setupBackHandling() {
        // 用 OnBackPressedDispatcher 而不是覆写已废弃的 onBackPressed()，
        // 这样在 Android 13+ 的预测性返回手势下行为也正确。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    // ---------------------------------------------------------------- 导航
    private fun openFromIntent(intent: Intent?) {
        val server = prefs.serverUrl
        if (server.isEmpty()) {
            showSetupBar(getString(R.string.setup_hint))
            return
        }
        val ticketId = UrlRules.parseTicketId(intent?.data)
        if (ticketId != null) {
            openTicket(ticketId)
        } else {
            loadUrl("$server/")
        }
    }

    /** 打开某张工单的网页（通知点击、深链、网页内 oridesk:// 链接都汇到这里）。 */
    private fun openTicket(ticketId: Long) {
        val server = prefs.serverUrl
        if (server.isEmpty()) {
            showSetupBar(getString(R.string.setup_hint))
            return
        }
        loadUrl(UrlRules.ticketWebUrl(server, ticketId))
    }

    private fun loadUrl(url: String) {
        loadedServer = prefs.serverUrl
        webView.loadUrl(url)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------------------------------------------------------------- 引导条
    private fun refreshSetupBar() {
        val server = prefs.serverUrl
        when {
            server.isEmpty() -> showSetupBar(getString(R.string.setup_hint))
            // 有地址但还没有会话 cookie：多半是还没登录，或会话已过期。
            !ApiClient(server).hasSessionCookie -> showSetupBar(getString(R.string.err_need_login))
            else -> binding.setupBar.visibility = View.GONE
        }
    }

    private fun showSetupBar(text: String) {
        binding.setupText.text = text
        binding.setupBar.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 权限
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
