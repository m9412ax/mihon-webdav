package eu.kanade.tachiyomi.source.smb

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * SY --> SMB/NAS 阅读源配置。
 *
 * 与 WebDAV 侧（`DownloadPreferences` 的 `cloudSync*`）、网盘侧（[eu.kanade.tachiyomi.source.netdisk.NetdiskPreferences]）
 * 同级：只存连接凭据，不落任何派生状态。凭据用明文字符串存 PreferenceStore，
 * 与 WebDAV 的 username/password、夸克的 Cookie 同一级保护。
 *
 * 与网盘的关键差异：SMB 是**真实文件路径**协议，所以不需要 id↔名字映射，也不需要
 * 「取直链再走 HTTP Range」——章节路径本身就是共享内路径，读取走 SMB2 READ 的原生 offset。
 *
 * ⚠️ 目录语义：挂载点复用 `NetdiskPreferences.netdiskMounts`（记录格式
 * `"<routeKey>|<目录路径>|<显示名>"`，routeKey = [SmbSource.ROUTE_KEY]），
 * 这样「云盘」文件夹视图的挂载/刷新/删除链路完全复用，不必另起一套。
 * SY <--
 */
@Inject
@SingleIn(AppScope::class)
class SmbPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /** 服务器主机名或 IP（SMB 走 TCP，不能用 URL）。空 = 未配置。 */
    val host: Preference<String> = preferenceStore.getString("smb_host", "")

    /**
     * SMB 端口，445 是 SMB2/3 的标准端口（139 是 NetBIOS 会话服务，smbj 不支持）。
     * 存成字符串是为了与其它字段共用同一种输入控件；非法值在 [SmbClient] 里回退到 445。
     */
    val port: Preference<String> = preferenceStore.getString("smb_port", DEFAULT_PORT.toString())

    /**
     * 共享名。⚠️ 这里**不是**「要访问哪个共享」——路径本身已经带了共享名
     * （形如 `/<共享名>/子路径`，见 [SmbClient] 的路径模型）。
     *
     * 这个字段的唯一作用是**兜底**：当服务器拒绝 SRVSVC 共享枚举（禁用 IPC$、
     * 或账号没有枚举权限）时，列根目录会退回只给出这一个共享。正常情况留空即可，
     * 云盘里会直接列出服务器上的全部共享供你随意进入。
     */
    val share: Preference<String> = preferenceStore.getString("smb_share", "")

    /** 域 / 工作组。家用 NAS 与 Windows 工作组留空即可；加入域的机器才需要填。 */
    val domain: Preference<String> = preferenceStore.getString("smb_domain", "")

    val username: Preference<String> = preferenceStore.getString("smb_username", "")

    val password: Preference<String> = preferenceStore.getString("smb_password", "")

    /**
     * 是否已配置。与网盘源同口径（凭据填了就算配置好），不设独立的启用开关：
     * 少一个「填了却忘打开」的坑，连接是否真的可用由设置页的「测试连接」验证。
     *
     * 共享名不在必填之列 —— 路径已经带了共享名，这一项只是枚举被拒时的兜底。
     */
    fun isConfigured(): Boolean = host.get().isNotBlank() && username.get().isNotBlank()

    /**
     * 上次「测试连接」的完整结果文本（含根因与因果链）。
     *
     * 为什么要单独存起来显示：toast 宽度有限会把长错误**截断**（真机上就是这样把
     * `ExecutionException: com.hie…` 截掉的），而设置项的 subtitle 会换行、能完整看到。
     * 用 appStateKey —— 这是应用状态、不是用户设置，不该进备份，也不该被同步带走。
     */
    val lastTestResult: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("smb_last_test_result"),
        "",
    )

    companion object {
        const val DEFAULT_PORT = 445
    }
}
