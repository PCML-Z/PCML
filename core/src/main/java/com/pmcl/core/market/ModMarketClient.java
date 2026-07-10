package com.pmcl.core.market;

import okhttp3.OkHttpClient;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 模组市场客户端统一接口。
 */
public interface ModMarketClient {

    /**
     * 按关键字搜索项目。
     *
     * @param query       关键字（如 "sodium"）
     * @param gameVersion 可选 MC 版本过滤，null 表示不过滤
     * @param loader      可选加载器过滤（"fabric"/"forge"/"quilt"），null 表示不过滤
     * @param limit       返回数量上限
     */
    CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                String loader, int limit);

    /**
     * 按关键字 + 分类搜索项目（两者 AND 关系）。
     * 默认实现忽略 category（用于不支持分类过滤的平台，如 CurseForge）。
     *
     * @param query       关键字
     * @param gameVersion 可选 MC 版本过滤
     * @param loader      可选加载器过滤
     * @param category    可选分类（平台原生 slug，如 "performance"/"technology"）
     * @param limit       返回数量上限
     */
    default CompletableFuture<List<ModProject>> search(String query, String gameVersion,
                                                        String loader, String category, int limit) {
        return search(query, gameVersion, loader, limit);
    }

    /**
     * 按分类浏览项目（无关键字，按下载量排序）。
     * 用于「分类推荐」功能：用户点击分类标签后加载该分类下的热门项目。
     * 默认返回空列表（不支持分类浏览的平台）。
     *
     * @param category    分类 slug（平台原生）
     * @param gameVersion 可选 MC 版本过滤
     * @param loader      可选加载器过滤
     * @param limit       返回数量上限
     */
    default CompletableFuture<List<ModProject>> searchByCategory(String category, String gameVersion,
                                                                  String loader, int limit) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    /**
     * 获取某项目的所有可下载文件。
     */
    CompletableFuture<List<ModFile>> listFiles(String projectId);

    /**
     * 获取热门项目（按下载量/流行度排序，无关键字）。
     *
     * @param gameVersion 可选 MC 版本过滤，null 表示不过滤
     * @param loader      可选加载器过滤，null 表示不过滤
     * @param limit       返回数量上限
     */
    CompletableFuture<List<ModProject>> popular(String gameVersion, String loader, int limit);

    /**
     * 标识来源。
     */
    String source();

    /**
     * 更新 OkHttpClient 引用（用户在设置中修改代理后调用）。
     * 默认空实现，支持复用 DownloadManager http 客户端的客户端可覆盖。
     */
    default void updateHttpClient(OkHttpClient http) {}
}
