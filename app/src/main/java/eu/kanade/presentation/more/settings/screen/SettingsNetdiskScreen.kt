package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.sync.service.AliyunPanSyncService
import eu.kanade.tachiyomi.source.netdisk.cloud115open.Cloud115OpenApi
import eu.kanade.tachiyomi.ui.setting.track.AliyunPanQrLoginActivity
import eu.kanade.tachiyomi.ui.setting.track.QuarkQrLoginActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.preference.Preference as PreferenceData
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * SY 2026-10-05：一级设置项「网盘」——把**所有网盘相关入口归到同一屏**（用户要求）：
 *
 *  - 「网盘源（阅读）」：115 / 夸克 / 阿里云盘的登录、Cookie、测试连接（本屏实现，已从
 *    「数据与备份」整体移入）；
 *  - 「WebDAV」：登录/目标目录/上传选项的实现仍在「下载」设置里（`getCloudSyncGroup` 及其
 *    登录对话框是私有组件，无损搬运成本高），这里放一个直达入口；
 *  - 「备份同步（阿里云盘）」：同上，实现仍在「数据与备份」，放直达入口。
 *
 * 阿里云盘**阅读源**与备份同步**共用登录**：任一边扫码成功，另一边立即可用。
 */
object SettingsNetdiskScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_netdisk

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        return listOf(
            getNetdiskSourceGroup(),
            getSmbGroup(),
            Preference.PreferenceGroup(
                title = "WebDAV（网盘同步）",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        // SY 2026-10-19：「云盘同步」已按用户要求改名为「WebDAV 登录」
                        title = stringResource(MR.strings.cloud_sync),
                        subtitle = "WebDAV 登录、目标目录、上传后删除本地、并发上传数（在「下载」设置中配置）",
                        onClick = { navigator.push(SettingsDownloadScreen) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "备份同步（阿里云盘）",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "前往备份同步设置",
                        subtitle = "同步服务（阿里云盘）、备份范围、自动同步（在「数据与备份」中配置）",
                        onClick = { navigator.push(SettingsDataScreen) },
                    ),
                ),
            ),
            Preference.PreferenceItem.InfoPreference(
                "网盘源（115 / 夸克 / 阿里云盘）与 SMB / NAS 都不在「浏览 → 图源」里单独出现，" +
                    "统一从「书架 → 云盘 → 添加网盘 / SMB 文件夹」挂载目录后阅读，" +
                    "封面 / 流式 / 章节识别与 WebDAV 云盘完全一致。",
            ),
        )
    }

    /**
     * 网盘源（阅读）组 —— 自 SettingsDataScreen 移入（2026-10-05），并补上
     * 夸克扫码登录与阿里云盘（阅读源）条目。
     */
    @Composable
    private fun getNetdiskSourceGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val netdiskPreferences = remember { context.appGraph.netdiskPreferences }
        val quarkApi = remember { context.appGraph.quarkApi }
        val quarkSource = remember { context.appGraph.quarkSource }
        val aliyunPanService = remember { context.appGraph.aliyunPanService }
        val aliyunSyncService = remember { context.appGraph.aliyunPanSyncService }
        val aliyunPanSource = remember { context.appGraph.aliyunPanSource }
        val syncPreferences = remember { context.appGraph.syncPreferences }

        val quarkCookie = remember { netdiskPreferences.quarkCookie }
        // SY 2026-10-08：115 开放平台（OAuth）令牌对
        val cloud115OpenApi = remember { context.appGraph.cloud115OpenApi }
        val cloud115OpenAccessToken = remember { netdiskPreferences.cloud115OpenAccessToken }
        val cloud115OpenRefreshToken = remember { netdiskPreferences.cloud115OpenRefreshToken }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_netdisk) + "（阅读）",
            preferenceItems = listOf(
                Preference.PreferenceItem.InfoPreference(
                    "登录后图源会出现在「浏览 → 图源」，浏览与阅读体验和 WebDAV 文件夹一致。" +
                        "阿里云盘与下方「备份同步」共用登录。",
                ),
                // ===================== 115 开放平台（OAuth，SY 2026-10-08） =====================
                Preference.PreferenceItem.InfoPreference(
                    "115 开放平台 = 官方 OAuth 授权链路（与上面的 Cookie 模式双轨并存，可同时配置）。" +
                        "点「获取令牌」打开 api.oplist.org：下拉选「115 验证网盘」→ 勾选「使用 OpenList 提供的参数」→ " +
                        "115 App 扫码授权 → 把得到的访问令牌和刷新令牌分别粘贴到下面两项。",
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "115 开放平台：获取令牌",
                    subtitle = "打开 api.oplist.org（OpenList 官方令牌工具页）",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(Intent.ACTION_VIEW, Uri.parse(Cloud115OpenApi.TOKEN_HELPER_URL)),
                            )
                        }.onFailure { context.toast("未找到可打开网页的应用") }
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = "115 开放平台：访问令牌（Access Token）",
                    subtitle = if (cloud115OpenAccessToken.get().isNotBlank()) "已配置" else "未配置（oplist.org 获取后粘贴）",
                    preference = cloud115OpenAccessToken,
                    onValueChanged = { newValue ->
                        scope.launch {
                            cloud115OpenAccessToken.set(newValue.trim())
                            if (newValue.trim().isNotBlank()) cloud115OpenApi.invalidateList()
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = "115 开放平台：刷新令牌（Refresh Token）",
                    subtitle = if (cloud115OpenRefreshToken.get().isNotBlank()) "已配置（过期会自动刷新）" else "未配置（oplist.org 获取后粘贴）",
                    preference = cloud115OpenRefreshToken,
                    onValueChanged = { newValue ->
                        scope.launch {
                            cloud115OpenRefreshToken.set(newValue.trim())
                            if (newValue.trim().isNotBlank()) cloud115OpenApi.invalidateList()
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "115 开放平台：测试连接",
                    subtitle = "注意：每个账号在同一应用最多 2 个刷新令牌，第 3 次获取会顶掉最早的",
                    onClick = {
                        scope.launch {
                            val ok = runCatching { cloud115OpenApi.check() }.isSuccess
                            context.toast(if (ok) "115 开放平台连接成功" else "115 开放平台连接失败（令牌可能无效）")
                        }
                    },
                ),
                // ===================== 夸克 =====================
                Preference.PreferenceItem.TextPreference(
                    title = "夸克网盘：扫码登录",
                    subtitle = "用夸克手机 App 扫码确认，Cookie 自动保存，无需手动复制",
                    onClick = { context.startActivity(QuarkQrLoginActivity.newIntent(context)) },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = "夸克网盘：Cookie",
                    subtitle = if (quarkCookie.get().isNotBlank()) {
                        "已配置（也可手动粘贴 pan.quark.cn 的 Cookie）"
                    } else {
                        "未配置"
                    },
                    preference = quarkCookie,
                    onValueChanged = { newValue ->
                        scope.launch {
                            val trimmed = newValue.trim()
                            quarkCookie.set(trimmed)
                            quarkApi.resetSession()
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "夸克网盘：测试连接",
                    onClick = {
                        scope.launch {
                            val ok = runCatching { quarkApi.check() }.isSuccess
                            context.toast(if (ok) "夸克连接成功" else "夸克连接失败（Cookie 可能已失效）")
                        }
                    },
                ),
                // ===================== 阿里云盘（阅读源） =====================
                Preference.PreferenceItem.TextPreference(
                    title = "阿里云盘：扫码登录",
                    subtitle = if (syncPreferences.aliyunPanRefreshToken.get().isNotBlank()) {
                        "已登录（与「备份同步」共用；可重新扫码切换账号）"
                    } else {
                        "未登录"
                    },
                    onClick = { context.startActivity(AliyunPanQrLoginActivity.newIntent(context)) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "阿里云盘：测试连接",
                    onClick = {
                        scope.launch {
                            val result = aliyunSyncService.testConnection()
                            when (result) {
                                AliyunPanSyncService.ConnectionTestStatus.SUCCESS ->
                                    context.toast("阿里云盘连接成功")
                                AliyunPanSyncService.ConnectionTestStatus.NOT_CONFIGURED ->
                                    context.toast("阿里云盘未登录")
                                else -> context.toast("阿里云盘连接失败（登录可能已过期）")
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    "阿里云盘阅读源与「备份同步」共用登录：下方同步服务里登录后，" +
                    "图源「阿里云盘」立即可浏览（挂载点在图源内「添加文件夹」填目录名或 fileId）。",
                ),
            ),
        )
    }

    /**
     * SY 2026-11-05：SMB / NAS 阅读源配置。与网盘源同级，但没有扫码登录那一套 ——
     * 连接参数就是「主机 + 共享 + 账号」，填完用「测试连接」验证。
     *
     * 每改一项都要 [eu.kanade.tachiyomi.source.smb.SmbClient.reset]：否则客户端仍持有指向
     * 旧服务器 / 旧账号的会话，界面看着改好了、读到的还是旧共享。
     */
    @Composable
    private fun getSmbGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val smbPreferences = remember { context.appGraph.smbPreferences }
        val smbClient = remember { context.appGraph.smbClient }

        // subtitle 必须显式给：EditTextPreference 的默认值是 "%s"，会把当前值原样显示在列表里，
        // 密码字段因此绝不能走默认值
        fun smbField(title: String, subtitle: String, preference: PreferenceData<String>) =
            Preference.PreferenceItem.EditTextPreference(
                title = title,
                subtitle = subtitle,
                preference = preference,
                onValueChanged = { newValue ->
                    preference.set(newValue.trim())
                    smbClient.reset()
                    true
                },
            )

        // ⚠️ 这里一律**不**显示「已填/未填」：subtitle 是组合期算出来的，填完保存后列表不会
        // 重组，界面上会停留在旧状态（真机上就出现过「未填用户名」却已经拿用户名去连的假象）。
        // 配置是否真的可用，一律以下面「上次测试结果」那行为准。
        return Preference.PreferenceGroup(
            title = "SMB / NAS（阅读）",
            preferenceItems = listOf(
                Preference.PreferenceItem.InfoPreference(
                    "只读访问 SMB 共享（群晖 / 威联通 / Windows 共享 / Samba 等），把 NAS 上的漫画" +
                    "当成一个图源读。仅支持 SMB2 / SMB3 —— 服务器若只开了 SMB1，需先在 NAS 侧启用 SMB2。\n\n" +
                    "路径形如「/<共享名>/子路径」：云盘根层会直接列出这台服务器上的所有共享，" +
                    "点进去就是该共享的内容，不用先决定连哪个共享。" +
                    "配置好后到「书架 → 云盘 → 添加网盘 / SMB 文件夹」挂载要看的目录。",
                ),
                smbField("SMB：主机", "NAS 的 IP 或主机名，例如 192.168.1.10", smbPreferences.host),
                smbField("SMB：端口", "默认 445；留空或填错都按 445 处理", smbPreferences.port),
                smbField(
                    "SMB：兜底共享名（可留空）",
                    "留空即可。只有当服务器禁止枚举共享（禁用 IPC\$ 或账号无枚举权限）时，" +
                        "才需要在这里填一个已知共享名，根层会退回只显示它。",
                    smbPreferences.share,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "SMB：测试共享枚举…",
                    subtitle = "单独试一次「能不能列出共享」，据此判断要不要填上面那个兜底项",
                    onClick = {
                        scope.launch {
                            smbClient.enumerateShares()
                                .onSuccess { shares ->
                                    val diskShares = shares.filter { it.isDiskTree && !it.isAdminShare }
                                    showSmbMessage(
                                        context,
                                        "SMB 共享枚举",
                                        if (diskShares.isEmpty()) {
                                            "能连上，但没有任何可浏览的文件共享（共 ${shares.size} 项，" +
                                                "都是 IPC\$ / 打印机 / 结尾带 \$ 的管理共享）。" +
                                                "请在上面填一个已知共享名作为兜底。"
                                        } else {
                                            "可浏览共享 ${diskShares.size} 个：\n\n" +
                                                diskShares.joinToString("\n") { share ->
                                                    if (share.remark.isBlank()) share.name
                                                    else "${share.name}　（${share.remark}）"
                                                } +
                                                "\n\n到「书架 → 云盘 → 添加网盘 / SMB 文件夹」就能直接进这些共享。"
                                        },
                                    )
                                }
                                .onFailure { e ->
                                    showSmbMessage(
                                        context,
                                        "SMB 共享枚举失败",
                                        (e.message ?: e.javaClass.simpleName) +
                                            "\n\n如果连得上但枚举被拒，请在「兜底共享名」里填一个已知共享名。",
                                    )
                                }
                        }
                    },
                ),
                smbField("SMB：域 / 工作组", "家用 NAS 与 Windows 工作组留空即可，加域的机器才需要填", smbPreferences.domain),
                smbField("SMB：用户名", "NAS 上用于访问该共享的账号", smbPreferences.username),
                smbField("SMB：密码", "不会显示在列表里；也不会被写进备份或同步到云端", smbPreferences.password),
                Preference.PreferenceItem.TextPreference(
                    title = "SMB：测试连接",
                    subtitle = "建连并列出共享根目录；失败会弹出完整原因",
                    onClick = {
                        scope.launch {
                            if (!smbPreferences.isConfigured()) {
                                val msg = "未配置：主机与用户名必填（共享名与密码都可留空）"
                                smbPreferences.lastTestResult.set(msg)
                                context.toast(msg)
                                return@launch
                            }
                            smbClient.testConnection()
                                .onSuccess {
                                    smbPreferences.lastTestResult.set("连接成功：$it")
                                    context.toast("SMB 连接成功")
                                }
                                .onFailure { e ->
                                    val msg = e.message ?: e.javaClass.simpleName
                                    smbPreferences.lastTestResult.set("连接失败：$msg")
                                    showSmbMessage(context, "SMB 连接失败", msg)
                                }
                        }
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    smbPreferences.lastTestResult.get().ifBlank {
                        "上次测试结果：还没测过。填好上面几项后点「SMB：测试连接」。"
                    },
                ),
            ),
        )
    }
}

/**
 * SMB 相关的长文本提示统一走对话框：toast 在真机上被限制成两行，
 * 恰好会把 smbj 的根因与 SRVSVC 的 NT 状态码截掉，而那正是排查所需。
 */
private fun showSmbMessage(context: android.content.Context, title: String, message: String) {
    android.app.AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("好", null)
        .show()
}
