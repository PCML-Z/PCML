package com.pmcl.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.pmcl.core.i18n.I18n
import com.google.gson.reflect.TypeToken
import com.pmcl.core.cache.DataCache
import com.pmcl.core.web.WikiBrowser


/**
 * M29 拆分：新闻域。
 */

/** 保护 _newsItems 的 read-modify-write，避免并发封面图回填竞态 */
private val newsUpdateLock = Mutex()

// ============ 新闻 ============

/**
 * 拉取 Minecraft.net 官方 RSS 新闻。
 * 进入新闻页时自动调用一次；网络失败会通过 status 反馈并保留旧数据。
 */
fun LauncherViewModel.refreshNews() {
    if (_newsLoading.value) return
    scope.launch {
        // 先读缓存秒开
        val cached = withContext(Dispatchers.IO) {
            DataCache.loadWithTimestamp("news_list", object : TypeToken<List<com.pmcl.core.news.NewsItem>>() {})
        }
        if (cached != null) {
            @Suppress("UNCHECKED_CAST")
            val data = cached[0] as? List<com.pmcl.core.news.NewsItem> ?: return@launch
            val savedAt = cached[1] as? Long ?: return@launch
            if (data.isNotEmpty()) {
                _newsItems.value = data
                _newsLoading.value = false
                fetchNewsCoverImages(data)
            }
            // 缓存未过期：后台静默刷新（stale-while-revalidate）
            if (!DataCache.isExpired(savedAt, 60 * 60 * 1000L)) {
                scope.launch {
                    try {
                        val list = withContext(Dispatchers.IO) {
                            core.news().fetch(20).join()
                        }
                        transferNewsImageUrls(list)
                        _newsItems.value = list
                        fetchNewsCoverImages(list)
                        DataCache.save("news_list", list)
                        _status.value = if (list.isEmpty()) I18n.t("status.no_news") else I18n.t("status.news_loaded", list.size)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        throw kotlinx.coroutines.CancellationException()
                    } catch (_: Throwable) {
                        // 静默失败，保留缓存数据
                    }
                }
                return@launch
            }
            // 缓存已过期：继续走正常网络请求
        }
        // 缓存不存在/已过期：正常网络请求
        _newsLoading.value = true
        _status.value = I18n.t("status.loading_news")
        try {
            val list = withContext(Dispatchers.IO) {
                core.news().fetch(20).join()
            }
            transferNewsImageUrls(list)
            _newsItems.value = list
            fetchNewsCoverImages(list)
            _status.value = if (list.isEmpty()) I18n.t("status.no_news") else I18n.t("status.news_loaded", list.size)
            DataCache.save("news_list", list)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.news_load_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _newsLoading.value = false
        }
    }
}

/** 将旧列表中已抓取的 imageUrl 按 link 迁移到新列表，避免后台刷新时重复抓取 */
@PublishedApi
internal fun LauncherViewModel.transferNewsImageUrls(newItems: List<com.pmcl.core.news.NewsItem>) {
    val oldMap = _newsItems.value.associateBy { it.getLink() }
    newItems.forEach { newItem ->
        val old = oldMap[newItem.getLink()]
        if (old != null && old.getImageUrl().isNotEmpty()) {
            newItem.setImageUrl(old.getImageUrl())
        }
    }
}

/**
 * RSS 不含图片 URL，异步抓取每篇文章页提取封面图并回填到 NewsItem。
 * 并发限制 5，完成后更新缓存。已有 imageUrl 的条目跳过。
 */
@PublishedApi
internal fun LauncherViewModel.fetchNewsCoverImages(items: List<com.pmcl.core.news.NewsItem>) {
    newsImageJob?.cancel()
    val toFetch = items.filter { it.getImageUrl().isEmpty() && it.getLink().isNotEmpty() }
    if (toFetch.isEmpty()) return
    newsImageJob = scope.launch {
        val semaphore = Semaphore(5)
        coroutineScope {
            toFetch.forEach { item ->
                launch {
                    semaphore.withPermit {
                        try {
                            val url = withContext(Dispatchers.IO) {
                                core.news().fetchCoverImage(item.getLink()).join()
                            }
                            if (url.isNotEmpty()) {
                                newsUpdateLock.withLock {
                                    item.setImageUrl(url)
                                    _newsItems.value = _newsItems.value.toList()
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        DataCache.save("news_list", _newsItems.value)
    }
}

/** 在系统浏览器打开新闻原文链接 */
fun LauncherViewModel.openNewsLink(url: String) {
    if (url.isBlank()) {
        _status.value = I18n.t("status.news_no_link")
        return
    }
    scope.launch {
        try {
            withContext(Dispatchers.IO) { WikiBrowser.open(url) }
            _status.value = I18n.t("status.news_opened_in_browser")
        } catch (e: Throwable) {
            _status.value = I18n.t("status.open_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 加载新闻文章正文（在 PMCL 内部展示） */
fun LauncherViewModel.loadArticle(url: String) {
    if (url.isBlank()) {
        _articleError.value = "该新闻没有可访问的链接"
        return
    }
    if (_articleLoading.value) return
    val cacheKey = "article_" + url.hashCode()
    scope.launch {
        // 先读缓存（永久缓存，命中即返回）
        val cached = withContext(Dispatchers.IO) {
            DataCache.load(cacheKey, object : TypeToken<com.pmcl.core.news.ArticleContent>() {})
        }
        if (cached != null) {
            _articleContent.value = cached
            _articleError.value = ""
            _articleLoading.value = false
            return@launch
        }
        // 缓存不存在：网络请求
        _articleLoading.value = true
        _articleError.value = ""
        _articleContent.value = null
        try {
            val content = withContext(Dispatchers.IO) {
                core.news().fetchArticle(url).join()
            }
            _articleContent.value = content
            DataCache.save(cacheKey, content)
        } catch (e: Throwable) {
            _articleError.value = "加载文章失败：${e.message}"
        } finally {
            _articleLoading.value = false
        }
    }
}

/** 退出文章详情视图 */
fun LauncherViewModel.clearArticle() {
    _articleContent.value = null
    _articleError.value = ""
}

