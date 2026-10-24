package com.pmcl.ui.viewmodel

/** 一条政策变动。文案在 i18n 的 policy.entry.<id>.title / policy.entry.<id>.body。 */
data class PolicyChange(
    val id: String,
    /** 生效日期，ISO 形式，仅作展示。 */
    val date: String,
    /** 该政策随哪个版本开始生效。 */
    val version: String,
    /** 重要条目在界面上高亮，用于合规这类必须让用户看到的变动。 */
    val important: Boolean = false
)

/**
 * 最近的政策变动，新的排在前面。
 *
 * 随应用内置而不是从网络拉取：合规通知不能受网络或第三方服务可用性影响，
 * 也不能因为用户关掉「GitHub Release 同步」就看不到。
 */
object PolicyChangelog {
    val entries: List<PolicyChange> = listOf(
        PolicyChange("download_restricted", "2026-10-24", "2.7.1", important = true),
        PolicyChange("community_client_restricted", "2026-10-24", "2.7.1", important = true),
        PolicyChange("server_restricted", "2026-10-24", "2.7.1", important = true),
        PolicyChange("true_egress_ip", "2026-10-24", "2.7.1"),
        PolicyChange("forced_update", "2026-10-24", "2.7.1")
    )
}
