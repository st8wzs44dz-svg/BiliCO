package erl.bilico

import android.content.Intent
import erl.bilico.ui.ExternalVideoLink

internal object ExternalVideoLinkIntent {
    fun fromIntent(intent: Intent?): ExternalVideoLink? {
        if (intent == null) return null
        // 从系统最近任务拉起被杀掉的进程时，系统会重放冷启动时的外链 Intent，不能再跳进去。
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()?.let { ExternalVideoLink.fromUrl(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ExternalVideoLink.fromText(text)
            }
            else -> null
        }
    }
}
