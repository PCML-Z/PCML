package com.pmcl.core.market;

import okhttp3.OkHttpClient;

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
