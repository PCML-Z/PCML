package com.lash.pmcl.core.market

import okhttp3.OkHttpClient
import java.util.concurrent.CompletableFuture

/**
 * Mod 市场客户端接口。
 *
 * Android 版本：从 Java 移植，纯接口，无平台依赖。
 */
interface ModMarketClient {
    fun search(query: String, gameVersion: String, loader: String, limit: Int):
        CompletableFuture<List<ModProject>>

    fun search(query: String, gameVersion: String, loader: String, category: String, limit: Int):
        CompletableFuture<List<ModProject>> {
        return search(query, gameVersion, loader, limit)
    }

    fun searchByCategory(category: String, gameVersion: String, loader: String, limit: Int):
        CompletableFuture<List<ModProject>> {
        return search("", gameVersion, loader, category, limit)
    }

    fun listFiles(projectId: String): CompletableFuture<List<ModFile>>

    fun popular(gameVersion: String, loader: String, limit: Int): CompletableFuture<List<ModProject>>

    fun source(): String

    fun updateHttpClient(http: OkHttpClient) {}
}
