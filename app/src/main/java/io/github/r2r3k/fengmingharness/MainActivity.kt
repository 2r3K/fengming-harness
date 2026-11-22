package io.github.r2r3k.fengmingharness

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.r2r3k.fengmingharness.core.PendingShare
import io.github.r2r3k.fengmingharness.core.SettingsStore
import io.github.r2r3k.fengmingharness.core.UiText
import io.github.r2r3k.fengmingharness.core.UiTheme
import io.github.r2r3k.fengmingharness.core.store.Housekeeping
import io.github.r2r3k.fengmingharness.ui.chat.ChatScreen
import io.github.r2r3k.fengmingharness.ui.theme.PocketHarnessTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectShared(intent)
        // 字号的档位存在本地，进门前先读出来，免得先按标准画一次再跳
        val store = SettingsStore(this)
        UiText.load(store)
        UiTheme.load(store)
        // 后台小扫除：把上次没写完的回答标成已中断、清掉遗留的临时 zip。
        // 放线程里跑，不挡启动；失败也只是记日志。
        val appContext = applicationContext
        Thread {
            runCatching { Housekeeping.onStart(appContext) }
        }.apply { isDaemon = true }.start()
        setContent {
            PocketHarnessTheme(darkMode = UiTheme.mode) {
                // 只放大"字"，不放大 dp：边距、按钮大小不动，屏幕不会被挤爆
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = base.density,
                        fontScale = base.fontScale * UiText.factor,
                    )
                ) {
                    ChatScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        collectShared(intent)
    }

    /** 处理"分享到"和"用其他应用打开"传来的文件 */
    private fun collectShared(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) uris += uri
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list?.let { uris += it }
            }

            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
        }

        if (uris.isNotEmpty()) {
            PendingShare.uris = uris
        }
    }
}
