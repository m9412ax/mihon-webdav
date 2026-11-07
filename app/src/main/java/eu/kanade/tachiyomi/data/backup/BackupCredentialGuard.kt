package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupPreference

/**
 * SY --> 备份落盘 / 云端同步**之前**的凭据清洗。
 *
 * 为什么需要它：`PreferenceBackupCreator` 已经用 `Preference.isAppState` 滤掉了应用状态、
 * 用 `Preference.isPrivate` 滤掉了「私有」键，但**这个 fork 的一批真凭据是明文键**，
 * 两层过滤都拦不住 —— 作者注释里点的「1.0.29 泄漏 115 Open token」就是这个洞：
 * `netdisk_115_open_access_token` / `netdisk_115_open_refresh_token` 在 `NetdiskPreferences`
 * 里是普通 `getString` 键，于是被原样写进备份并同步上云。
 *
 * 另一条必要路径在 `SyncManager`：把合并结果**回推**到远端前也要洗一遍。远端可能还存着
 * 旧版本（过滤上线前、或 1.0.29 泄漏期间）同步上去的 Cookie / 令牌，不洗就会被一直
 * 带着来回传，并且恢复到本机 —— 所以清洗必须同时守在「导出」和「回推」两个口上。
 *
 * 取舍：连 `*_username` 一起剥离，意味着从备份恢复后用户要重新填账号。这是刻意的——
 * 用户名和具体账号绑定，泄漏价值低于令牌但也属于凭据；宁可让用户重填一次。
 *
 * ⚠️ 已知未覆盖：`createSource()` 导出的**图源扩展配置**（`BackupSourcePreferences`）
 * 没有走本清洗。扩展配置里同样可能存 Cookie / token，但原实现只在 app 偏好上调用了
 * `withoutCredentials`，这里不擅自扩大改动面，只在注释里留痕。
 */
object BackupCredentialGuard {

    /**
     * 明确存放凭据的明文键。逐条来自各 `*Preferences` 的定义，不是猜的：
     *  - `cloud_sync_*`：WebDAV 云同步的 Basic 认证（`DownloadPreferences`）
     *  - `netdisk_*`：115 开放平台令牌对、夸克 Cookie（`NetdiskPreferences`）
     *  - `sync_client_api_key`：旧同步服务的客户端密钥（`SyncPreferences` 里唯一的明文例外，
     *    同类的 `google_drive_*_token` / `aliyun_pan_*_token` / `webdav_password` 都走
     *    `Preference.appStateKey`，本就会被 isAppState 滤掉）
     *  - `smb_*`：SMB/NAS 阅读源的账号口令（`SmbPreferences`）
     */
    private val CREDENTIAL_KEYS = setOf(
        "cloud_sync_username",
        "cloud_sync_password",
        "netdisk_115_open_access_token",
        "netdisk_115_open_refresh_token",
        "netdisk_quark_cookie",
        "sync_client_api_key",
        "smb_username",
        "smb_password",
    )

    /**
     * 兜底后缀规则：新增凭据键忘了登记到上面时，只要命名跟着 `*_token` / `*_cookie` /
     * `*_password` / `*_api_key` / `*_secret` 的既有惯例，就不会再泄漏一次。
     * 用 `endsWith` 而不是 `contains`：本仓库现有键里没有任何非凭据项以这些后缀结尾
     * （`sync_etag`、`netdisk_mounts`、`smb_host` / `smb_port` / `smb_share` / `smb_domain`
     * 都不匹配），因此不会误删正常设置。
     */
    private val CREDENTIAL_KEY_SUFFIXES = listOf(
        "_token",
        "_cookie",
        "_password",
        "_api_key",
        "_secret",
    )

    /**
     * 剥掉凭据项，其余原样保留（顺序也不动，避免影响 `SyncManager` 的差异比对）。
     * 以函数引用形式被调用（`BackupCredentialGuard::withoutCredentials`），所以保持
     * `(List<BackupPreference>) -> List<BackupPreference>` 的形状。
     */
    fun withoutCredentials(preferences: List<BackupPreference>): List<BackupPreference> =
        preferences.filterNot { preference -> isCredentialKey(preference.key) }

    private fun isCredentialKey(key: String): Boolean =
        key in CREDENTIAL_KEYS || CREDENTIAL_KEY_SUFFIXES.any { suffix -> key.endsWith(suffix) }
}
