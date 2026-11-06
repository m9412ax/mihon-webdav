package eu.kanade.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.vr.VrDepthDiskCache
import eu.kanade.tachiyomi.util.vr.VrDepthModel
import eu.kanade.tachiyomi.util.vr.VrDepthModelStore
import eu.kanade.tachiyomi.util.vr.VrStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

/**
 * SY --> 头显设置页。
 *
 * 放在阅读器内的设置弹窗、而不是「设置 → 阅读器」：屏幕大小、距离、立体强度、视差方向
 * 这些参数只有在戴着头显看画面时才判断得了，跑去系统设置里调完再回来等于白调。
 *
 * 深度模型要显式下载：最小的也有 27 MB，不该在用户没打算用 VR 时悄悄占掉存储。
 */
@Composable
internal fun ColumnScope.VrPage(viewModel: ReaderSettingsViewModel) {
    val context = LocalActivity.current ?: return
    val prefs = viewModel.preferences

    val store = remember { VrDepthModelStore(VrStorage.modelRoot(context)) }
    // collectAsState() 返回的是只读 State：写回必须走 pref.set()，
    // 不然界面改了但偏好没动，VrViewer 那边读到的还是旧值。
    val selectedId by prefs.vrDepthModel.collectAsState()
    var readyIds by remember { mutableStateOf(emptySet<String>()) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf<String?>(null) }

    HorizontalDivider()
    ListGroupHeader(stringResource(MR.strings.vr_depth_model))

    VrDepthModel.entries.forEach { model ->
        CheckboxItem(
            label = model.label,
            checked = selectedId == model.id,
            onClick = {
                prefs.vrDepthModel.set(model.id)
                failure = null
            },
        )
    }

    LaunchedEffect(selectedId) {
        readyIds = withContext(Dispatchers.IO) {
            VrDepthModel.entries.filter { store.isReady(it) }.map { it.id }.toSet()
        }
    }

    val selected = VrDepthModel.fromId(selectedId)
    val downloaded = readyIds.contains(selected.id)
    if (downloadingId == selected.id) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    } else if (!downloaded) {
        TextButton(onClick = {
            failure = null
            fraction = 0f
            downloadingId = selected.id
        }) {
            Text(stringResource(MR.strings.vr_download))
        }
    } else {
        Text(
            text = stringResource(MR.strings.vr_downloaded),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    failure?.let { message ->
        Text(
            text = stringResource(MR.strings.vr_download_failed, message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    // 下载挂在这个 state 上，而不是写在按钮的 onClick 里：按钮一点就可能因组合变化而
    // 连同其回调所在的作用域一起被取消，留下半截 .part 文件。
    LaunchedEffect(downloadingId) {
        val id = downloadingId ?: return@LaunchedEffect
        val model = VrDepthModel.fromId(id)
        try {
            withContext(Dispatchers.IO) {
                store.download(model) { written, total ->
                    fraction = if (total > 0) written.toFloat() / total else 0f
                }
            }
            readyIds = VrDepthModel.entries.filter { store.isReady(it) }.map { it.id }.toSet()
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            downloadingId = null
        }
    }

    HorizontalDivider()
    CheckboxItem(label = stringResource(MR.strings.vr_depth_enable), pref = prefs.vrDepthEnabled)

    val strengthPref = prefs.vrDepthStrengthMeters
    val strength by strengthPref.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.vr_depth_strength),
        value = (strength * 100).toInt(),
        valueRange = 0..60,
        valueString = String.format(Locale.US, "%.2f m", strength),
        onChange = { strengthPref.set(it / 100f) },
    )

    val distancePref = prefs.vrDistanceMeters
    val distance by distancePref.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.vr_screen_distance),
        value = (distance * 100).toInt(),
        valueRange = 80..600,
        steps = 25,
        valueString = String.format(Locale.US, "%.2f m", distance),
        onChange = { distancePref.set(it / 100f) },
    )

    val widthPref = prefs.vrScreenWidthDegrees
    val width by widthPref.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.vr_screen_size),
        value = width.toInt(),
        valueRange = 8..50,
        steps = 20,
        valueString = "${width.toInt()}°",
        onChange = { widthPref.set(it.toFloat()) },
    )

    val prefetchPref = prefs.vrPrefetchPages
    val prefetch by prefetchPref.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.vr_prefetch_pages),
        value = prefetch,
        valueRange = 0..8,
        onChange = { prefetchPref.set(it) },
    )

    HorizontalDivider()
    CheckboxItem(label = stringResource(MR.strings.vr_head_locked), pref = prefs.vrHeadLocked)
    CheckboxItem(
        label = stringResource(MR.strings.vr_invert_parallax),
        pref = prefs.vrInvertParallax,
    )
    CheckboxItem(
        label = stringResource(MR.strings.vr_invert_buttons),
        pref = prefs.vrInvertButtons,
    )

    TextButton(onClick = {
        // 清缓存要遍历几千个小文件，放主线程会卡住这层对话框。
        CoroutineScope(Dispatchers.IO).launch {
            VrDepthDiskCache(
                VrStorage.depthCacheRoot(context),
                prefs.vrDepthCacheMb.get() * 1024L * 1024L,
            ).clear()
        }
    }) {
        Text(stringResource(MR.strings.vr_clear_depth_cache))
    }
}
