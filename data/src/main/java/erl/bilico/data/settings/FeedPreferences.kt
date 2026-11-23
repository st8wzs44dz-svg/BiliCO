package erl.bilico.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import erl.bilico.data.biliDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 推荐流封面的裁切比例。[ratio] 是宽高比，封面按它裁成固定高度。 */
enum class CoverAspect(val code: Int, val label: String, val ratio: Float) {
    WIDE(0, "16:9", 16f / 9f),
    CLASSIC(1, "4:3", 4f / 3f),
    ;

    companion object {
        fun fromCode(code: Int): CoverAspect = entries.firstOrNull { it.code == code } ?: CLASSIC
    }
}

/** 推荐流/榜单卡片底部翻转模糊信息区的着色方案。 */
enum class FeedCoverBlurStyle(val code: Int, val label: String, val summary: String) {
    VIBRANT_DARK(
        code = 0,
        label = "高彩度深色",
        summary = "统一深色玻璃底与白字，提纯封面色相并避免暗部发灰，深浅色模式一致",
    ),
    ADAPTIVE_TONE(
        code = 1,
        label = "自适应明暗",
        summary = "浅色模式下亮色封面呈现粉彩亮底与深色字，深色封面及深色模式自动保持高彩度深色",
    ),
    ;

    companion object {
        fun fromCode(code: Int): FeedCoverBlurStyle =
            entries.firstOrNull { it.code == code } ?: VIBRANT_DARK
    }
}

/** 信息流的外观与刷新选项：封面比例、封面模糊底色、推荐列数、单次下拉刷新拉取数量。 */
class FeedPreferences(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _coverAspect = MutableStateFlow(CoverAspect.CLASSIC)
    val coverAspect: StateFlow<CoverAspect> = _coverAspect.asStateFlow()

    private val _coverBlurStyle = MutableStateFlow(FeedCoverBlurStyle.VIBRANT_DARK)
    val coverBlurStyle: StateFlow<FeedCoverBlurStyle> = _coverBlurStyle.asStateFlow()

    private val _columns = MutableStateFlow(DEFAULT_COLUMNS)
    val columns: StateFlow<Int> = _columns.asStateFlow()

    private val _refreshCount = MutableStateFlow(DEFAULT_REFRESH_COUNT)
    val refreshCount: StateFlow<Int> = _refreshCount.asStateFlow()

    suspend fun restore() {
        val prefs = context.biliDataStore.data.first()
        _coverAspect.value = CoverAspect.fromCode(prefs[KEY_COVER_ASPECT] ?: CoverAspect.CLASSIC.code)
        _coverBlurStyle.value = FeedCoverBlurStyle.fromCode(
            prefs[KEY_COVER_BLUR_STYLE] ?: FeedCoverBlurStyle.VIBRANT_DARK.code,
        )
        _columns.value = (prefs[KEY_COLUMNS] ?: DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        _refreshCount.value = (prefs[KEY_REFRESH_COUNT] ?: DEFAULT_REFRESH_COUNT)
            .coerceIn(MIN_REFRESH_COUNT, MAX_REFRESH_COUNT)
    }

    fun setCoverAspect(aspect: CoverAspect) {
        if (aspect == _coverAspect.value) return
        _coverAspect.value = aspect
        scope.launch { context.biliDataStore.edit { it[KEY_COVER_ASPECT] = aspect.code } }
    }

    fun setCoverBlurStyle(style: FeedCoverBlurStyle) {
        if (style == _coverBlurStyle.value) return
        _coverBlurStyle.value = style
        scope.launch { context.biliDataStore.edit { it[KEY_COVER_BLUR_STYLE] = style.code } }
    }

    fun setColumns(columns: Int) {
        val resolved = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        if (resolved == _columns.value) return
        _columns.value = resolved
        scope.launch { context.biliDataStore.edit { it[KEY_COLUMNS] = resolved } }
    }

    fun setRefreshCount(count: Int) {
        val resolved = count.coerceIn(MIN_REFRESH_COUNT, MAX_REFRESH_COUNT)
        if (resolved == _refreshCount.value) return
        _refreshCount.value = resolved
        scope.launch { context.biliDataStore.edit { it[KEY_REFRESH_COUNT] = resolved } }
    }

    companion object {
        const val MIN_COLUMNS = 1
        const val MAX_COLUMNS = 4
        const val DEFAULT_COLUMNS = 2
        const val MIN_REFRESH_COUNT = 6
        const val MAX_REFRESH_COUNT = 30
        const val DEFAULT_REFRESH_COUNT = 20
        val REFRESH_COUNT_OPTIONS = listOf(6, 8, 10, 12, 15, 16, 20, 24, 30)

        private val KEY_COVER_ASPECT = intPreferencesKey("feed_cover_aspect")
        private val KEY_COVER_BLUR_STYLE = intPreferencesKey("feed_cover_blur_style")
        private val KEY_COLUMNS = intPreferencesKey("feed_columns")
        private val KEY_REFRESH_COUNT = intPreferencesKey("feed_refresh_count")
    }
}
