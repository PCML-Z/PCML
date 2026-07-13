package com.pmcl.core.instance;

import com.pmcl.core.LauncherConfig;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 独立实例管理器。
 * <p>
 * 统一管理 {@code ~/.pmcl/instances/<instanceId>/} 目录下的所有实例，包括：
 * <ul>
 *   <li>用户手动创建的自定义实例（Type.CUSTOM）</li>
 *   <li>从整合包导入的实例（Type.MODPACK，向后兼容 modpack.json）</li>
 * </ul>
 * <p>
 * 每个实例目录包含：
 * <ul>
 *   <li>{@code instance.json} — 实例元数据标记文件（新格式）</li>
 *   <li>{@code modpack.json} — 旧格式整合包标记（向后兼容读取）</li>
 *   <li>{@code mods/ saves/ config/ resourcepacks/ shaderpacks/ screenshots/ logs/}</li>
 * </ul>
 */
public final class InstanceManager {

    private static final String INSTANCE_MARKER = "instance.json";
    private static final String LEGACY_MODPACK_MARKER = "modpack.json";
    private static final String[] SUBDIRS = {
        "mods", "saves", "config", "resourcepacks", "shaderpacks", "screenshots", "logs"
    };

    private final LauncherConfig config;

    public InstanceManager(LauncherConfig config) {
        this.config = config;
    }

    /** 实例根目录 {@code ~/.pmcl/instances/} */
    public Path getInstancesDir() {
        return config.getWorkDir().resolve("instances");
    }

    /** 获取指定实例的目录路径 */
    public Path getInstanceDir(String instanceId) {
        return getInstancesDir().resolve(instanceId);
    }

    /**
     * 列出所有实例（扫描 instances/ 目录，读取标记文件）。
     * 同时支持新格式 instance.json 和旧格式 modpack.json。
     */
    public List<InstanceInfo> listInstances() {
        Path dir = getInstancesDir();
        if (!Files.isDirectory(dir)) return Collections.emptyList();

        List<InstanceInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                  .sorted(Comparator.comparing(p -> getDirMtime(p), Comparator.reverseOrder()))
                  .forEach(instanceDir -> {
                      InstanceInfo info = loadInstanceInfo(instanceDir);
                      if (info != null) result.add(info);
                  });
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return result;
    }

    /** 读取实例元数据（优先 instance.json，回退 modpack.json） */
    private InstanceInfo loadInstanceInfo(Path instanceDir) {
        try {
            Path newMarker = instanceDir.resolve(INSTANCE_MARKER);
            Path legacyMarker = instanceDir.resolve(LEGACY_MODPACK_MARKER);

            if (Files.exists(newMarker)) {
                String json = Files.readString(newMarker);
                return InstanceInfo.fromJson(json, instanceDir);
            }
            if (Files.exists(legacyMarker)) {
                String json = Files.readString(legacyMarker);
                return InstanceInfo.fromModpackJson(json, instanceDir);
            }
            // 无标记文件但存在 mods/ 子目录（versionIsolation 创建的目录）
            if (Files.isDirectory(instanceDir.resolve("mods"))) {
                String dirName = instanceDir.getFileName().toString();
                InstanceInfo info = new InstanceInfo(UUID.randomUUID().toString(),
                    dirName, dirName, InstanceInfo.Type.CUSTOM);
                info.setInstanceDir(instanceDir);
                return info;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 创建新实例。
     *
     * @param name           实例显示名称
     * @param baseVersionId  基础 Minecraft 版本 ID
     * @param loader         模组加载器（可为 null）
     * @param loaderVersion  加载器版本（可为 null）
     * @return 创建的实例信息
     */
    public InstanceInfo createInstance(String name, String baseVersionId,
                                       String loader, String loaderVersion) throws IOException {
        String instanceId = UUID.randomUUID().toString();
        Path instanceDir = getInstanceDir(instanceId);
        Files.createDirectories(instanceDir);
        for (String sub : SUBDIRS) {
            Files.createDirectories(instanceDir.resolve(sub));
        }

        InstanceInfo info = new InstanceInfo(instanceId, name, baseVersionId, InstanceInfo.Type.CUSTOM);
        info.setLoader(loader);
        info.setLoaderVersion(loaderVersion);
        info.setInstanceDir(instanceDir);
        saveInstanceInfo(info);
        return info;
    }

    /**
     * 复制现有实例（克隆 mods/configs/resourcepacks，不复制 saves/logs）。
     *
     * @param sourceId 源实例 ID
     * @param newName  新实例名称
     * @return 新实例信息
     */
    public InstanceInfo copyInstance(String sourceId, String newName) throws IOException {
        Path sourceDir = getInstanceDir(sourceId);
        if (!Files.isDirectory(sourceDir)) throw new IOException("源实例不存在: " + sourceId);

        InstanceInfo source = loadInstanceInfo(sourceDir);
        if (source == null) throw new IOException("无法读取源实例元数据");

        String newId = UUID.randomUUID().toString();
        Path newDir = getInstanceDir(newId);
        Files.createDirectories(newDir);

        // 复制 mods / config / resourcepacks / shaderpacks（不复制 saves/screenshots/logs 避免占空间）
        for (String sub : new String[]{"mods", "config", "resourcepacks", "shaderpacks"}) {
            Path srcSub = sourceDir.resolve(sub);
            if (Files.isDirectory(srcSub)) {
                copyDirectory(srcSub, newDir.resolve(sub));
            } else {
                Files.createDirectories(newDir.resolve(sub));
            }
        }
        // 创建空的 saves/screenshots/logs
        for (String sub : new String[]{"saves", "screenshots", "logs"}) {
            Files.createDirectories(newDir.resolve(sub));
        }

        InstanceInfo newInfo = new InstanceInfo(newId, newName, source.getBaseVersionId(), InstanceInfo.Type.CUSTOM);
        newInfo.setLoader(source.getLoader());
        newInfo.setLoaderVersion(source.getLoaderVersion());
        newInfo.setDescription(source.getDescription());
        newInfo.setInstanceDir(newDir);
        saveInstanceInfo(newInfo);
        return newInfo;
    }

    /** 重命名实例（仅修改 name 字段，目录名不变） */
    public void renameInstance(String instanceId, String newName) throws IOException {
        Path dir = getInstanceDir(instanceId);
        InstanceInfo info = loadInstanceInfo(dir);
        if (info == null) throw new IOException("实例不存在");
        info.setName(newName);
        saveInstanceInfo(info);
    }

    /** 更新实例元数据 */
    public void updateInstance(InstanceInfo info) throws IOException {
        saveInstanceInfo(info);
    }

    /** 删除实例（递归删除整个目录） */
    public void deleteInstance(String instanceId) throws IOException {
        Path dir = getInstanceDir(instanceId);
        if (Files.isDirectory(dir)) {
            deleteDirectory(dir);
        }
    }

    /** 保存实例元数据到 instance.json */
    public void saveInstanceInfo(InstanceInfo info) throws IOException {
        Files.createDirectories(info.getInstanceDir());
        Files.writeString(info.getInstanceDir().resolve(INSTANCE_MARKER), info.toJson());
    }

    /** 获取实例目录（用于启动时设置 gameDir） */
    public Path resolveInstanceDir(String instanceId) {
        return getInstanceDir(instanceId);
    }

    /** 确保实例子目录存在 */
    public void ensureSubdirs(Path instanceDir) throws IOException {
        for (String sub : SUBDIRS) {
            Files.createDirectories(instanceDir.resolve(sub));
        }
    }

    // ===== 内部工具方法 =====

    private static long getDirMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dst = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        }
    }
}
