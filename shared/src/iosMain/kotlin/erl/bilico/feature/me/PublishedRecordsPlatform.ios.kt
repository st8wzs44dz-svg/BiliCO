package erl.bilico.feature.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberJsonFileExchanger(): JsonFileExchanger = remember {
    object : JsonFileExchanger {
        override val supported: Boolean = false
        override fun exportJson(suggestedFileName: String, provideContent: suspend () -> String) = Unit
        override fun importJson(onContent: suspend (String) -> Unit) = Unit
    }
}
