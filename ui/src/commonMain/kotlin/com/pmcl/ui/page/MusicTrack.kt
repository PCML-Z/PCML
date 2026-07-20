package com.pmcl.ui.page

/**
 * 播放列表数据类。
 *
 * 仅持久化元数据（不持久化 audioUrl，因为 B站/A站 audio URL 有 2 小时过期），
 * 播放时调用 AudioSourceResolver.resolve(sourceUrl) 重新获取新 audioUrl。
 *
 * 序列化由 Gson 完成（无需 @Serializable 注解）。
 */
data class MusicTrack(
    val sourceUrl: String,      // 用户输入的原始 URL
    val title: String,
    val uploader: String,
    val durationMs: Long,       // 0 表示未知
    val coverUrl: String,       // 可空用 ""
    val sourceType: String,     // "bilibili" / "acfun" / "direct"
    val originalId: String      // BV号/ac号等
)
