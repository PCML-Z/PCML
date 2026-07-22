package com.pmcl.core.instance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pmcl.core.mods.ModMeta;
import com.pmcl.core.mods.ModScanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 实例导出器：将实例导出为可分享的 .pmcl-instance（ZIP）文件。
 * <p>
 * 导出内容：
 * <ul>
 *   <li>{@code instance.json} — 实例元数据（精简版：去掉 instanceId、boundAccountUuid、
 *       lastPlayedAt、totalPlayTimeSeconds 等运行时字段，保留 name/baseVersionId/loader/
 *       loaderVersion/description）</li>
 *   <li>{@code mods.json} — 模组清单（每个 mod 的 modId/version/name/loader/jarFile/disabled），
 *       不含 jar 本体（版权 + 体积考量），导入时根据清单重新下载</li>
 *   <li>{@code config/} — 配置文件目录（模组配置，体积小且不可从公开源恢复）</li>
 *   <li>{@code icon.*} — 实例图标（如有）</li>
 * </ul>
 * <p>
 * 不导出：mods/（jar 本体）、saves/（存档）、screenshots/（截图）、logs/（日志）、
 * resourcepacks/（资源包，体积可能很大）、shaderpacks/（光影包，体积大且有版权）。
 */
public final class InstanceExporter {

    private static final Gson gson = new Gson();

    private InstanceExporter() {}

    /**
     * 导出实例到指定 zip 文件路径。
     *
     * @param info       实例元数据
     * @param outputPath 输出 zip 文件路径
     * @return 导出的模组数量（用于 UI 提示）
     * @throws IOException 导出失败
     */
    public static int export(InstanceInfo info, Path outputPath) throws IOException {
        Path instanceDir = info.getInstanceDir();
        if (instanceDir == null || !Files.isDirectory(instanceDir)) {
            throw new IOException("实例目录不存在");
        }

        Files.createDirectories(outputPath.getParent());
        int modCount = 0;

        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(outputPath), StandardCharsets.UTF_8)) {

            // 1. 写入精简版 instance.json
            JsonObject metaJson = new JsonObject();
            metaJson.addProperty("name", info.getName());
            metaJson.addProperty("baseVersionId", info.getBaseVersionId());
            metaJson.addProperty("type", info.getType().name());
            if (info.getLoader() != null) metaJson.addProperty("loader", info.getLoader());
            if (info.getLoaderVersion() != null) metaJson.addProperty("loaderVersion", info.getLoaderVersion());
            if (info.getDescription() != null) metaJson.addProperty("description", info.getDescription());
            metaJson.addProperty("exportFormat", "pmcl-instance");
            metaJson.addProperty("exportVersion", 1);
            writeZipEntry(zos, "instance.json", metaJson.toString().getBytes(StandardCharsets.UTF_8));

            // 2. 扫描 mods 目录并写入 mods.json 清单
            Path modsDir = instanceDir.resolve("mods");
            if (Files.isDirectory(modsDir)) {
                List<ModMeta> mods = ModScanner.scanDirectory(modsDir);
                modCount = mods.size();
                JsonArray modsArray = new JsonArray();
                for (ModMeta mod : mods) {
                    JsonObject modObj = new JsonObject();
                    if (mod.getModId() != null) modObj.addProperty("modId", mod.getModId());
                    if (mod.getVersion() != null) modObj.addProperty("version", mod.getVersion());
                    if (mod.getName() != null) modObj.addProperty("name", mod.getName());
                    if (mod.getLoader() != null) modObj.addProperty("loader", mod.getLoader());
                    if (mod.getJarFile() != null) modObj.addProperty("jarFile", mod.getJarFile());
                    modObj.addProperty("disabled", mod.isDisabled());
                    // 依赖列表（帮助导入时检查缺失前置）
                    if (mod.getDepends() != null && !mod.getDepends().isEmpty()) {
                        JsonArray deps = new JsonArray();
                        for (String dep : mod.getDepends()) deps.add(dep);
                        modObj.add("depends", deps);
                    }
                    modsArray.add(modObj);
                }
                writeZipEntry(zos, "mods.json", gson.toJson(modsArray).getBytes(StandardCharsets.UTF_8));
            }

            // 3. 复制 config/ 目录（模组配置文件，体积小且无法从公开源恢复）
            Path configDir = instanceDir.resolve("config");
            if (Files.isDirectory(configDir)) {
                addDirectoryToZip(zos, configDir, "config/");
            }

            // 4. 复制图标文件（如有）
            String iconPath = info.getIconPath();
            if (iconPath != null && !iconPath.isEmpty()) {
                Path iconFile = instanceDir.resolve(iconPath);
                if (Files.exists(iconFile)) {
                    String zipEntryName = Paths.get(iconPath).getFileName().toString();
                    writeZipEntry(zos, zipEntryName, Files.readAllBytes(iconFile));
                }
            }
        }

        return modCount;
    }

    /** 写入 zip 条目 */
    private static void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    /** 递归将目录添加到 zip */
    private static void addDirectoryToZip(ZipOutputStream zos, Path dir, String zipPrefix) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.forEach(p -> {
                try {
                    String relative = dir.relativize(p).toString().replace(FileSystems.getDefault().getSeparator(), "/");
                    String zipPath = zipPrefix + relative;
                    if (Files.isDirectory(p)) {
                        if (!zipPath.endsWith("/")) zipPath += "/";
                        ZipEntry entry = new ZipEntry(zipPath);
                        zos.putNextEntry(entry);
                        zos.closeEntry();
                    } else {
                        ZipEntry entry = new ZipEntry(zipPath);
                        zos.putNextEntry(entry);
                        Files.copy(p, zos);
                        zos.closeEntry();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
