package com.pmcl.ui.viewmodel

import com.pmcl.core.i18n.I18n
import com.pmcl.core.update.ReleaseAnnouncements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ANNOUNCEMENT_CACHE_MS = 10 * 60 * 1000L

/**
 * 关于页打开时拉取 GitHub Releases 作为更新公告。
 * 十分钟内重复进入不重复请求；刷新按钮强制重拉。
 */
fun LauncherViewModel.refreshReleaseAnnouncements(force: Boolean = false) {
    if (_releaseAnnouncementsLoading.value) return
    val now = System.currentTimeMillis()
    if (!force
        && _releaseAnnouncementsError.value.isEmpty()
        && _releaseAnnouncements.value.isNotEmpty()
        && now - releaseAnnouncementsFetchedAt < ANNOUNCEMENT_CACHE_MS
    ) {
        return
    }
    val repo = preferences.getGithubRepo().trim()
    scope.launch {
        _releaseAnnouncementsLoading.value = true
        _releaseAnnouncementsError.value = ""
        try {
            val items = withContext(Dispatchers.IO) {
                ReleaseAnnouncements.fetch(repo)
            }
            _releaseAnnouncements.value = items
            releaseAnnouncementsFetchedAt = System.currentTimeMillis()
        } catch (e: Throwable) {
            _releaseAnnouncementsError.value = announcementError(e.message ?: "")
        } finally {
            _releaseAnnouncementsLoading.value = false
        }
    }
}

private fun announcementError(code: String): String = when {
    code == "invalid-repo" -> I18n.t("about.announcements_invalid_repo")
    code == "not-found" -> I18n.t("about.announcements_not_found")
    code == "rate-limit" -> I18n.t("about.announcements_rate_limit")
    code == "too-large" -> I18n.t("about.announcements_failed")
    code.startsWith("http-") -> I18n.t("about.announcements_http", code.removePrefix("http-"))
    else -> I18n.t("about.announcements_failed")
}
