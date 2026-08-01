package com.lash.pmcl.core.modloader

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * PMCL 扩展：构造版本 JSON 中的 `pmclAgents` 字段。
 *
 * Android 版本：仅保留纯 JSON 工具方法。
 * 桌面版的 `inject` 已移除——Android 版 LaunchProfile 不支持 Java Agent 注入。
 *
 * `pmclAgents` 条目格式：
 * ```
 * { "name": "com.unascribed:nilloader:1.3.6", "url": "https://repo.sleeping.town/" }
 * ```
 */
object AgentLaunchSupport {

    /** group:artifact:version → maven 仓库相对路径。 */
    fun mavenPath(group: String, artifact: String, version: String): String {
        return group.replace('.', '/') + "/" + artifact + "/" + version +
                "/" + artifact + "-" + version + ".jar"
    }

    /** 构造单个 pmclAgents 条目。 */
    fun agentEntry(mavenName: String, repoUrl: String): JsonObject {
        val o = JsonObject()
        o.addProperty("name", mavenName)
        o.addProperty("url", repoUrl)
        return o
    }

    /** 构造仅含一个 agent 的 pmclAgents 数组。 */
    fun singleAgentArray(mavenName: String, repoUrl: String): JsonArray {
        val arr = JsonArray()
        arr.add(agentEntry(mavenName, repoUrl))
        return arr
    }
}
