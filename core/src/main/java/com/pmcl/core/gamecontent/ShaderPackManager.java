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

        public ShaderPack(String name, Path path, long size, boolean valid, boolean active) {
            this.name = name; this.path = path;
            this.size = size; this.valid = valid; this.active = active;
        }
        public String getName() { return name; }
        public Path getPath() { return path; }
        public long getSize() { return size; }
        public boolean isValid() { return valid; }
        public boolean isActive() { return active; }
    }

    public List<ShaderPack> list() throws IOException {
        List<ShaderPack> result = new ArrayList<>();
        if (!Files.isDirectory(shaderPacksDir)) return result;
        String active = readActiveShaderPack();
        try (Stream<Path> stream = Files.list(shaderPacksDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        try {
                            long size = Files.size(p);
                            boolean valid = hasShadersDir(p);
                            boolean isActive = name.equals(active) ||
                                    stripZipSuffix(name).equals(active);
                            result.add(new ShaderPack(name, p, size, valid, isActive));
                        } catch (IOException ignored) {}
                    });
        }
        return result;
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
            Files.createDirectories(optionsFile.getParent());
            Files.writeString(optionsFile, key + ":" + value + "\n");
            return;
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(optionsFile));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + ":")) {
                lines.set(i, key + ":" + value);
                found = true;
                break;
            }
        }
        if (!found) lines.add(key + ":" + value);
        Files.writeString(optionsFile, String.join("\n", lines) + "\n");
    }

    /** 校验 zip 内是否含 shaders/ 目录 */
    private boolean hasShadersDir(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            return zip.getEntry("shaders/") != null
                    || zip.stream().anyMatch(e -> e.getName().startsWith("shaders/"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 从 options.txt 读取当前选中的光影包名 */
    private String readActiveShaderPack() {
        if (!Files.exists(optionsFile)) return null;
        try {
            for (String line : Files.readAllLines(optionsFile)) {
                if (line.startsWith("shaderPack:")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) return parts[1].trim();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static String stripZipSuffix(String s) {
        return s.toLowerCase().endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
    }
}
