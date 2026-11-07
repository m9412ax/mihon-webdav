package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * SY --> 备份 / 云端同步格式里的「保存的搜索」占位模型。
 *
 * mihonW 本身没有「保存的搜索」功能（`SyncManager` 里该字段一律传 `emptyList()`），
 * 这个类存在的唯一理由是**保住云端备份格式的可解码性**：`Backup.backupSavedSearches`
 * 带了 `@ProtoNumber(600)`，元素类型必须自身可序列化，否则整个 `Backup` 生成不出
 * serializer（CI 上那条 `Serializer was not found for type 'BackupSavedSearch'`）。
 *
 * 字段号沿用 J2K / Mihon 的 SavedSearch 布局（1 = name、4 = source），但**故意不声明**
 * query(2) / filters(3)：那两个类型在本仓库里并不存在，硬写会变成新的未解析引用；
 * 而 protobuf 解码会跳过未知字段号，所以即便云端备份真带着这两个字段也能正常读，
 * 只是本源用不到。
 *
 * 用 `data class` 而非普通 class：`SyncManager` 靠
 * `remoteBackup.backupSavedSearches != backup.backupSavedSearches` 判断有无变化，需要结构相等。
 */
@Serializable
data class BackupSavedSearch(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(4) val source: Long = 0L,
)
