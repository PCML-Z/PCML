package com.pmcl.core.gamecontent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * 光影包管理：扫描 shaderpacks 目录，识别 .zip 光影包。
 * <p>
 * 光影包是 .zip 文件，内部包含 shaders/ 目录（Iris/OptiFine 规范）。
 * 当前选中状态由 options.txt 的 "shaderPack" 字段记录（仅作展示，不修改）。
 */
public final class ShaderPackManager {

    private final Path shaderPacksDir;
    private final Path optionsFile;

    public ShaderPackManager(Path workDir) {
        this.shaderPacksDir = workDir.resolve("shaderpacks");
        this.optionsFile = workDir.resolve("options.txt");
    }

    public Path getShaderPacksDir() { return shaderPacksDir; }

    public static final class ShaderPack {
        private final String name;
        private final Path path;
        private final long size;
        private final boolean valid;        // 是否含 shaders/ 目录
        private final boolean active;       // 是否为当前选中
        private final boolean disabled;     // 是否被禁用（.zip.disabled）
        private String source;              // 来源标签（"全局" / "外部" 等）

        public ShaderPack(String name, Path path, long size, boolean valid,
                          boolean active, boolean disabled, String source) {
            this.name = name; this.path = path;
            this.size = size; this.valid = valid; this.active = active;
            this.disabled = disabled; this.source = source;
        }
        public String getName() { return name; }
        public Path getPath() { return path; }
        public long getSize() { return size; }
        public boolean isValid() { return valid; }
        public boolean isActive() { return active; }
        public boolean isDisabled() { return disabled; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    public List<ShaderPack> list() throws IOException {
        return list(shaderPacksDir, "全局");
    }

    /** 扫描指定目录下的光影包，附带来源标签 */
    public List<ShaderPack> list(Path dir, String source) throws IOException {
        List<ShaderPack> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        String active = readActiveShaderPack();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
                        boolean disabled = false;
                        String display;
                        // 识别 .zip.disabled 与 .zip 两种文件
                        if (lower.endsWith(".zip.disabled")) {
                            disabled = true;
                            display = fileName.substring(0, fileName.length() - ".disabled".length());
                        } else if (lower.endsWith(".zip")) {
                            display = fileName;
                        } else {
                            return;
                        }
                        try {
                            long size = Files.size(p);
                            boolean valid = hasShadersDir(p);
                            // active 比较时使用去除 .disabled 后的显示名
                            boolean isActive = display.equals(active) ||
                                    stripZipSuffix(display).equals(active);
                            result.add(new ShaderPack(display, p, size, valid, isActive, disabled, source));
                        } catch (Throwable ignored) {}
                    });
        }
        return result;
    }

    /**
     * 启用光影包：将 xxx.zip.disabled 重命名为 xxx.zip。
     * 已启用的文件不变；若目标 xxx.zip 已存在，则删除 .disabled 副本。
     * @return 新文件名（启用后）
     */
    public String enable(String fileName) throws IOException {
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".disabled")) return fileName;
        Path src = shaderPacksDir.resolve(fileName).normalize();
        if (!src.startsWith(shaderPacksDir)) throw new IOException("非法文件名: " + fileName);
        String enabledName = fileName.substring(0, fileName.length() - ".disabled".length());
        Path dst = shaderPacksDir.resolve(enabledName).normalize();
        if (!dst.startsWith(shaderPacksDir)) throw new IOException("非法文件名: " + enabledName);
        if (!Files.exists(src)) throw new IOException("文件不存在: " + fileName);
        // 目标已存在（同名 zip 已启用）→ 删除禁用副本
        if (Files.exists(dst)) {
            Files.delete(src);
            return enabledName;
        }
        Files.move(src, dst);
        return enabledName;
    }

    /**
     * 禁用光影包：将 xxx.zip 重命名为 xxx.zip.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    public String disable(String fileName) throws IOException {
        if (fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".disabled")) return fileName;
        Path src = shaderPacksDir.resolve(fileName).normalize();
        if (!src.startsWith(shaderPacksDir)) throw new IOException("非法文件名: " + fileName);
        Path dst = shaderPacksDir.resolve(fileName + ".disabled").normalize();
        if (!dst.startsWith(shaderPacksDir)) throw new IOException("非法文件名: " + fileName);
        if (!Files.exists(src)) throw new IOException("文件不存在: " + fileName);
        Files.move(src, dst);
        return dst.getFileName().toString();
    }

    public void delete(ShaderPack pack) throws IOException {
        Files.deleteIfExists(pack.getPath());
    }

    /**
     * 将指定光影包设为当前选中（写入 options.txt 的 shaderPack 字段）。
     * 传入 null 表示关闭光影（设为空）。
     * <p>
     * 注意：options.txt 中 shaderPack 字段记录的是不含 .zip 后缀的名称。
     * 游戏运行时修改不会生效，需在游戏未运行时调用。
     */
    public void setActive(ShaderPack pack) throws IOException {
        String value = (pack == null) ? "" : stripZipSuffix(pack.getName());
        writeOption("shaderPack", value);
        // 同时开启 enableShaders 选项
        writeOption("enableShaders", "true");
    }

    /** 关闭光影（清空当前选中） */
    public void clearActive() throws IOException {
        writeOption("shaderPack", "");
    }

    /**
     * 写入/更新 options.txt 中的某个键值对，保留其它行。
     * 若文件不存在则新建；若键已存在则更新，否则追加。
     */
    private void writeOption(String key, String value) throws IOException {
        if (!Files.exists(optionsFile)) {
            if (optionsFile.getParent() != null) Files.createDirectories(optionsFile.getParent());
            Files.writeString(optionsFile, key + ":" + value + "\n", java.nio.charset.StandardCharsets.UTF_8);
            return;
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(optionsFile, java.nio.charset.StandardCharsets.UTF_8));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + ":")) {
                lines.set(i, key + ":" + value);
                found = true;
                break;
            }
        }
        if (!found) lines.add(key + ":" + value);
        Files.writeString(optionsFile, String.join("\n", lines) + "\n", java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 校验 zip 内是否含 shaders/ 目录 */
    private boolean hasShadersDir(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            return zip.getEntry("shaders/") != null
                    || zip.stream().anyMatch(e -> e.getName().startsWith("shaders/"));
        } catch (Throwable e) {
            return false;
        }
    }

    /** 从 options.txt 读取当前选中的光影包名 */
    private String readActiveShaderPack() {
        if (!Files.exists(optionsFile)) return null;
        try {
            for (String line : Files.readAllLines(optionsFile, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.startsWith("shaderPack:")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) return parts[1].trim();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String stripZipSuffix(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
    }
}
