package com.xinjiyuan.oridesk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.xinjiyuan.oridesk.databinding.ActivitySettingsBinding
import org.json.JSONObject

/**
 * 设置页：服务器地址、登录入口、推送开关、测试推送、电池白名单引导。
 *
 * 所有网络调用都走 [Async]（后台线程 + 主线程回调），主线程不做 IO。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.serverInput.setText(prefs.serverUrl)
        binding.deviceLabelInput.setText(prefs.deviceLabel.ifEmpty { defaultDeviceLabel() })
        binding.ntfyTokenInput.setText(prefs.ntfyToken.orEmpty())
        binding.enablePushSwitch.isChecked = prefs.pushEnabled

        binding.saveServerButton.setOnClickListener { saveServer() }
        binding.openLoginButton.setOnClickListener { openLogin() }
        binding.enablePushSwitch.setOnCheckedChangeListener { _, checked -> onPushToggled(checked) }
        binding.testPushButton.setOnClickListener { sendTestPush() }
        binding.batteryButton.setOnClickListener { openBatterySettings() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------- 服务器
    private fun saveServer() {
        when (val result = UrlRules.normalizeServer(binding.serverInput.text.toString())) {
            is UrlRules.Result.Err -> toast(result.message)
            is UrlRules.Result.Ok -> {
                val previous = prefs.serverUrl
                prefs.serverUrl = result.url
                if (previous.isNotEmpty() && previous != result.url) {
                    // 换了服务器：topic 是**上一个服务器**签发的，对新服务器无效。
                    // 留着它会让 App 订阅一个永远不会有消息的频道 —— 表面"已启用推送"
                    // 实则静默失效，是最难排查的一类故障。
                    prefs.clearSubscription()
                    NtfyService.stop(this)
                    prefs.pushEnabled = false
                    binding.enablePushSwitch.isChecked = false
                }
                prefs.ntfyToken = binding.ntfyTokenInput.text.toString().trim()
                prefs.deviceLabel = binding.deviceLabelInput.text.toString().trim()
                toast("已保存")
                refreshStatus()
            }
        }
    }

    private fun openLogin() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    // ---------------------------------------------------------------- 推送
    private fun onPushToggled(enabled: Boolean) {
        prefs.pushEnabled = enabled
        if (!enabled) {
            NtfyService.stop(this)
            // 同时通知服务端别再往这个 topic 发（少发无用请求，也便于运维看出设备已下线）。
            val server = prefs.serverUrl
            val id = prefs.subscriptionId
            if (server.isNotEmpty() && id > 0) {
                Async.run(work = { ApiClient(server).setSubscriptionEnabled(id, false) })
            }
            refreshStatus()
            return
        }

        val server = prefs.serverUrl
        if (server.isEmpty()) {
            toast(getString(R.string.setup_hint))
            binding.enablePushSwitch.isChecked = false
            prefs.pushEnabled = false
            return
        }
        if (!ApiClient(server).hasSessionCookie) {
            // 没有会话就没法调 E3 注册。直接说清楚，而不是让用户面对一个
            // "开关打开了但什么也没发生"的状态。
            toast(getString(R.string.push_needs_login))
            binding.enablePushSwitch.isChecked = false
            prefs.pushEnabled = false
            return
        }
        prefs.ntfyToken = binding.ntfyTokenInput.text.toString().trim()
        prefs.deviceLabel = binding.deviceLabelInput.text.toString().trim()
        registerSubscription(server)
    }

    private fun registerSubscription(server: String) {
        binding.pushStatus.text = "正在注册…"
        Async.run(
            work = {
                val api = ApiClient(server)
                // 先调 E1：它会下发 csrftoken（契约 §2.3 / 决策 D9），
                // 否则紧接着的 E3 写操作会 403 csrf_failed。
                api.badge()
                api.createSubscription(prefs.deviceLabel, prefs.topic)
            },
            done = { json, error -> onRegisterDone(json, error) },
        )
    }

    private fun onRegisterDone(json: JSONObject?, error: Throwable?) {
        if (error != null || json == null) {
            val message = (error as? ApiClient.ApiException)?.userMessage
                ?: error?.message ?: "未知错误"
            binding.enablePushSwitch.isChecked = false
            prefs.pushEnabled = false
            refreshStatus()
            toast(getString(R.string.push_register_failed, message))
            return
        }

        val topic = json.optString("topic")
        val ntfyServer = json.optString("server")
        if (topic.isEmpty() || ntfyServer.isEmpty()) {
            // 服务端没配 ntfy_server_url 时就是这样。必须明说，否则用户只会看到
            // "推送已启用"却永远收不到东西。
            binding.enablePushSwitch.isChecked = false
            prefs.pushEnabled = false
            refreshStatus()
            toast("服务端未配置 ntfy 地址，请让管理员在「系统设置」里填写 ntfy_server_url")
            return
        }

        prefs.topic = topic
        prefs.ntfyServer = ntfyServer
        prefs.subscriptionId = json.optLong("id")
        NtfyService.start(this)
        refreshStatus()
    }

    private fun sendTestPush() {
        val server = prefs.serverUrl
        val id = prefs.subscriptionId
        if (server.isEmpty() || id <= 0) {
            toast(getString(R.string.push_disabled))
            return
        }
        binding.pushStatus.text = "正在发送测试推送…"
        Async.run(
            work = {
                val api = ApiClient(server)
                api.badge() // 取 CSRF cookie
                api.testPush(id)
            },
            done = { json, error ->
                refreshStatus()
                when {
                    error != null -> {
                        val message = (error as? ApiClient.ApiException)?.userMessage
                            ?: error.message ?: "未知错误"
                        toast(getString(R.string.test_push_fail, message))
                    }
                    json?.optBoolean("sent") == true -> toast(getString(R.string.test_push_ok))
                    else -> toast(getString(R.string.test_push_fail, json?.optString("error").orEmpty()))
                }
            },
        )
    }

    private fun refreshStatus() {
        binding.pushStatus.text = when {
            !prefs.isServerConfigured -> getString(R.string.setup_hint)
            !prefs.pushEnabled -> getString(R.string.push_disabled)
            prefs.hasSubscription -> getString(
                R.string.push_registered,
                prefs.ntfyServer.orEmpty(),
            )
            else -> getString(R.string.push_disabled)
        }
    }

    // ---------------------------------------------------------------- 电池
    /**
     * 打开系统的电池优化设置。
     *
     * 方案 B 的已知不可控项就是国产 ROM 杀后台（决策时明确接受）。这里只能
     * **引导**用户加白名单，无法代替用户操作，也无法保证所有 ROM 都生效。
     */
    private fun openBatterySettings() {
        val power = getSystemService<PowerManager>()
        if (power?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast("已在电池优化白名单中")
            return
        }
        // 先尝试直接弹"是否允许忽略电池优化"的对话框；部分 ROM 没有这个界面，
        // 就退回打开电池优化列表页让用户自己找。
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(direct)
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("无法打开电池设置，请手动在系统设置里为 OriDesk 关闭电池优化")
            }
        }
    }

    // ---------------------------------------------------------------- 杂项
    private fun defaultDeviceLabel(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ").trim()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
