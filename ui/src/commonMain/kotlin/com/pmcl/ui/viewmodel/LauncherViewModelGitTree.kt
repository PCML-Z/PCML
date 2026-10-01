package com.pmcl.ui.viewmodel

import com.pmcl.core.i18n.I18n
import com.pmcl.core.update.RepoCommitGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 先画本地缓存，再只向 GitHub 补尚未保存的提交。 */
fun LauncherViewModel.refreshRepoGitTree(force: Boolean = false) {
    if (_repoGitTreeLoading.value) return
    val repo = preferences.getGithubRepo().trim()
    scope.launch {
        if (_repoGitTree.value == null || force) {
            val cached = withContext(Dispatchers.IO) { RepoCommitGraph.loadCached(repo) }
            if (cached != null) _repoGitTree.value = cached
        }
        _repoGitTreeLoading.value = true
        _repoGitTreeError.value = ""
        try {
            val snapshot = withContext(Dispatchers.IO) { RepoCommitGraph.sync(repo) }
            _repoGitTree.value = snapshot
            repoGitTreeFetchedAt = System.currentTimeMillis()
        } catch (e: Throwable) {
            _repoGitTreeError.value = gitTreeError(e.message ?: "")
            val cached = withContext(Dispatchers.IO) { RepoCommitGraph.loadCached(repo) }
            if (cached != null) _repoGitTree.value = cached
        } finally {
            _repoGitTreeLoading.value = false
        }
    }
}

private fun gitTreeError(code: String): String = when {
    code == "invalid-repo" -> I18n.t("settings.git_tree.invalid_repo")
    code == "not-found" -> I18n.t("settings.git_tree.not_found")
    code == "rate-limit" -> I18n.t("settings.git_tree.rate_limit")
    code == "bad-branch" -> I18n.t("settings.git_tree.bad_branch")
    code == "too-large" -> I18n.t("settings.git_tree.too_large")
    code == "timeout" -> I18n.t("settings.git_tree.timeout")
    code.startsWith("http-") -> I18n.t("settings.git_tree.http", code.removePrefix("http-"))
    else -> I18n.t("settings.git_tree.failed")
}
