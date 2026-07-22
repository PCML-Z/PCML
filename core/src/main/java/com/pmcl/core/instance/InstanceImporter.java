package com.pmcl.core.instance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 实例导入器：从 .pmcl-instance（ZIP）文件导入实例。
 * <p>
 * 导入流程：
 * <ol>
 *   <li>读取 zip 中的 instance.json 获取实例元数据</li>
 *   <li>创建新实例目录（生成新 instanceId）</li>
 *   <li>解压 config/ 目录到新实例（含 ZipSlip 防护）</li>
 *   <li>解压图标文件（如有）</li>
 *   <li>读取 mods.json 模组清单并返回（UI 层据此引导用户重新下载模组）</li>
 *   <li>写入 instance.json（新 instanceId，清空 boundAccountUuid）</li>
 * </ol>
 * <p>
 * 模组 jar 不包含在导出包中（版权 + 体积考量），导入后需根据 mods.json 清单
 * 重新下载。返回的 {@link ImportResult} 包含模组清单，UI 层可据此提示用户。
 */
public final class InstanceImporter {

    private static final Gson gson = new Gson();

    private InstanceImporter() {}

    /**
     * 从 zip 文件导入实例。
     *
     * @param zipPath     导入的 zip 文件路径
     * @param manager     实例管理器（用于创建新实例目录）
     * @return 导入结果（含新实例信息和模组清单）
     * @throws IOException 导入失败
     */
    public static ImportResult import_(Path zipPath, InstanceManager manager) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new IOException("导入文件不存在: " + zipPath);
        }

        // 临时存储 zip 中的数据
        String instanceName = "Imported Instance";
        String baseVersionId = "";
        String loader = null;
        String loaderVersion = null;
        String description = null;
        String iconFileName = null;
        byte[] iconData = null;
        String modsJson = null;
        Path tempConfigDir = null;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;

                // ZipSlip 防护：拒绝绝对路径和 .. 路径
                if (name.contains("..") || name.startsWith("/")) {
                    System.err.println("[InstanceImporter] 跳过不安全条目: " + name);
                    continue;
                }

                if (name.equals("instance.json")) {
                    String json = readString(zis);
                    JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                    if (o.has("name")) instanceName = o.get("name").getAsString();
                    if (o.has("baseVersionId")) baseVersionId = o.get("baseVersionId").getAsString();
                    if (o.has("loader")) loader = o.get("loader").getAsString();
                    if (o.has("loaderVersion")) loaderVersion = o.get("loaderVersion").getAsString();
                    if (o.has("description")) description = o.get("description").getAsString();
                } else if (name.equals("mods.json")) {
                    modsJson = readString(zis);
                } else if (name.startsWith("config/")) {
                    // 解压 config 到临时目录，稍后移动到新实例目录
                    if (tempConfigDir == null) {
                        tempConfigDir = Files.createTempDirectory("pmcl-import-config");
                    }
                    String relative = name.substring("config/".length());
                    Path targetFile = tempConfigDir.resolve(relative).normalize();
                    // ZipSlip 二次防护：确保目标在临时目录内
                    if (!targetFile.startsWith(tempConfigDir)) {
                        System.err.println("[InstanceImporter] 跳过逃逸临时目录的条目: " + name);
                        continue;
                    }
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(zis, targetFile, StandardCopyOption.REPLACE_EXISTING);
                } else if (isIconFile(name)) {
                    iconFileName = Paths.get(name).getFileName().toString();
                    iconData = readAllBytes(zis);
                }
            }
        }

        // 创建新实例
        InstanceInfo newInfo = manager.createInstance(instanceName, baseVersionId, loader, loaderVersion);
        if (description != null) newInfo.setDescription(description);
        Path newInstanceDir = newInfo.getInstanceDir();

        // 移动 config 目录
        if (tempConfigDir != null && Files.isDirectory(tempConfigDir)) {
            Path targetConfig = newInstanceDir.resolve("config");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempConfigDir)) {
                for (Path src : stream) {
                    Path dst = targetConfig.resolve(src.getFileName());
                    Files.createDirectories(dst.getParent());
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                deleteRecursive(tempConfigDir);
            }
        }

        // 复制图标
        if (iconFileName != null && iconData != null) {
            Files.write(newInstanceDir.resolve(iconFileName), iconData);
            newInfo.setIconPath(iconFileName);
        }

        // 保存更新后的元数据
        manager.saveInstanceInfo(newInfo);

        // 解析模组清单
        java.util.List<ModEntry> modList = new java.util.ArrayList<>();
        if (modsJson != null && !modsJson.isEmpty()) {
            try {
                JsonArray arr = JsonParser.parseString(modsJson).getAsJsonArray();
                for (var elem : arr) {
                    JsonObject o = elem.getAsJsonObject();
                    ModEntry mod = new ModEntry();
                    mod.modId = safeStr(o, "modId");
                    mod.version = safeStr(o, "version");
                    mod.name = safeStr(o, "name");
                    mod.loader = safeStr(o, "loader");
                    mod.jarFile = safeStr(o, "jarFile");
                    mod.disabled = o.has("disabled") && o.get("disabled").getAsBoolean();
                    modList.add(mod);
                }
            } catch (Throwable t) {
                System.err.println("[InstanceImporter] 解析 mods.json 失败: " + t.getMessage());
            }
        }

        return new ImportResult(newInfo, modList);
    }

    private static boolean isIconFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private static String readString(InputStream is) throws IOException {
        return new String(readAllBytes(is), StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private static String safeStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 模组清单条目 */
    public static final class ModEntry {
        public String modId;
        public String version;
        public String name;
        public String loader;
        public String jarFile;
        public boolean disabled;

        @Override
        public String toString() {
            return name + " (" + modId + " v" + version + ", " + loader + ")";
        }
    }

    /** 导入结果 */
    public static final class ImportResult {
        public final InstanceInfo info;
        public final java.util.List<ModEntry> mods;

        public ImportResult(InstanceInfo info, java.util.List<ModEntry> mods) {
            this.info = info;
            this.mods = mods;
        }
    }
}
