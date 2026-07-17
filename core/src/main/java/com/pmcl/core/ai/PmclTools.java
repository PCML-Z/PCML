package com.pmcl.core.ai;

import com.pmcl.core.market.ModFile;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.market.ModProject;
import com.pmcl.core.modloader.ModLoader;
import com.pmcl.core.modloader.ModLoaderInstaller;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.modloader.ModLoaderVersion;
import com.pmcl.core.preferences.Preferences;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * AI 工具集：将 PMCL 的模组搜索/下载/安装功能暴露给 AI 智能体。
 * <p>
 * 每个标注 {@code @Tool} 的方法会被 LangChain4j 注册为可调用工具，
 * AI 根据用户意图自动选择调用。方法返回 String（给 AI 看的结果文本），
 * 内部通过 CompletableFuture.join() 同步等待现有 API 完成。
 */
public class PmclTools {

    private final ModMarketManager modMarket;
    private final ModLoaderManager modLoaders;
    private final Preferences preferences;
    private volatile Consumer<String> statusCallback;

    public PmclTools(ModMarketManager modMarket, ModLoaderManager modLoaders,
                     Preferences preferences) {
        this.modMarket = modMarket;
        this.modLoaders = modLoaders;
        this.preferences = preferences;
    }

    /** 设置状态回调，工具执行过程中向 UI 反馈进度文本 */
    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    private void notifyStatus(String status) {
        Consumer<String> cb = statusCallback;
        if (cb != null) cb.accept(status);
    }

    // -----------------------------------------------------------------------
    // 模组搜索与下载
    // -----------------------------------------------------------------------

    @Tool("搜索 Minecraft 模组。返回模组名称、项目 ID、下载量和简介。")
    public String searchMods(
            @P("搜索关键词，例如 sodium、optifine") String query,
            @P("游戏版本，例如 1.20.1") String gameVersion,
            @P("加载器类型：fabric / forge / quilt / neoforge") String loader) {
        notifyStatus("正在搜索模组: " + query);
        try {
            List<ModProject> results = modMarket.search(query, gameVersion, loader, 10).join();
            if (results.isEmpty()) return "未找到匹配的模组";
            StringBuilder sb = new StringBuilder("找到 ").append(results.size()).append(" 个模组:\n");
            for (ModProject p : results) {
                sb.append("- 名称: ").append(p.getName())
                        .append(" | 项目ID: ").append(p.getId())
                        .append(" | 下载量: ").append(p.getDownloadCount())
                        .append(" | ").append(p.getSummary() != null ? p.getSummary() : "")
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    @Tool("下载并安装指定模组。需要先通过 searchMods 获取模组的项目 ID。")
    public String installMod(
            @P("模组项目 ID（从 searchMods 结果获取）") String projectId,
            @P("游戏版本") String gameVersion) {
        notifyStatus("正在安装模组: " + projectId);
        try {
            // 通过 ModrinthClient 直接用 projectId 获取文件列表
            List<ModFile> files = modMarket.getModrinthClient().listFiles(projectId).join();
            if (files.isEmpty()) return "未找到可下载的文件";

            // 找到匹配游戏版本的最新文件
            ModFile target = null;
            for (ModFile f : files) {
                if (f.getGameVersions() != null && f.getGameVersions().contains(gameVersion)) {
                    target = f;
                    break;
                }
            }
            if (target == null) {
                // 没有精确匹配，取第一个文件
                target = files.get(0);
            }

            final ModFile finalTarget = target;
            modMarket.installMod(finalTarget, gameVersion, gameVersion, preferences, msg -> {
                notifyStatus(msg);
            }).join();

            return "模组安装成功: " + target.getFileName();
        } catch (Exception e) {
            return "安装失败: " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // Mod 加载器安装
    // -----------------------------------------------------------------------

    @Tool("列出指定 Mod 加载器在某个游戏版本的可用版本列表")
    public String listModLoaderVersions(
            @P("加载器类型：fabric / forge / quilt / neoforge") String loaderName,
            @P("游戏版本") String gameVersion) {
        notifyStatus("正在查询 " + loaderName + " 版本列表");
        try {
            ModLoader loader = parseLoader(loaderName);
            if (loader == null) return "未知加载器: " + loaderName + "，支持: fabric/forge/quilt/neoforge";
            ModLoaderInstaller installer = modLoaders.get(loader);
            List<ModLoaderVersion> versions = installer.listVersions(gameVersion).join();
            if (versions.isEmpty()) return "未找到 " + loaderName + " 适用于 " + gameVersion + " 的版本";

            StringBuilder sb = new StringBuilder("可用版本:\n");
            int count = 0;
            for (ModLoaderVersion v : versions) {
                sb.append("- ").append(v.getLoaderVersion())
                        .append(v.isStable() ? " (稳定版)" : " (测试版)")
                        .append("\n");
                if (++count >= 10) {
                    sb.append("... 共 ").append(versions.size()).append(" 个版本\n");
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool("安装 Mod 加载器到指定游戏版本")
    public String installModLoader(
            @P("加载器类型：fabric / forge / quilt / neoforge") String loaderName,
            @P("游戏版本") String gameVersion,
            @P("加载器版本号（从 listModLoaderVersions 获取）") String loaderVersion) {
        notifyStatus("正在安装 " + loaderName + " " + loaderVersion + " for MC " + gameVersion);
        try {
            ModLoader loader = parseLoader(loaderName);
            if (loader == null) return "未知加载器: " + loaderName + "，支持: fabric/forge/quilt/neoforge";
            ModLoaderInstaller installer = modLoaders.get(loader);
            installer.install(gameVersion, loaderVersion, p -> {
                notifyStatus(p.getStage() + " - " + p.getMessage());
            }).join();
            return loaderName + " " + loaderVersion + " 安装成功 (MC " + gameVersion + ")";
        } catch (Exception e) {
            return "安装失败: " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------------

    private ModLoader parseLoader(String name) {
        if (name == null) return null;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "fabric": return ModLoader.FABRIC;
            case "forge": return ModLoader.FORGE;
            case "quilt": return ModLoader.QUILT;
            case "neoforge":
            case "neo-forge":
            case "neo":
                return ModLoader.NEOFORGE;
            case "optifine":
            case "opti":
                return ModLoader.OPTIFINE;
            default: return null;
        }
    }
}
