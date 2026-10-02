package com.pmcl.core.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 把内嵌的输入法 Java Agent 解到 {@code boot/pmcl-ime-agent.jar}。
 * Agent 本身保持 Java 8，这里只负责释放资源。
 */
public final class ImeFixSupport {

    private ImeFixSupport() {}

    public static Path ensure(Path workDir) throws IOException {
        if (workDir == null) throw new IOException("workDir 为空");
        Path dest = workDir.resolve("boot").resolve("pmcl-ime-agent.jar");
        byte[] expected;
        try (InputStream in = ImeFixSupport.class.getResourceAsStream(
                "/com/pmcl/core/ime/pmcl-ime-agent.jar")) {
            if (in == null) {
                throw new IOException("缺少内嵌资源 com/pmcl/core/ime/pmcl-ime-agent.jar"
                        + "（请确认 :core:imeAgentJar 已构建）");
            }
            expected = in.readAllBytes();
        }
        if (Files.isRegularFile(dest) && Files.size(dest) == expected.length) {
            byte[] existing = Files.readAllBytes(dest);
            if (java.util.Arrays.equals(existing, expected)) {
                return dest;
            }
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.write(tmp, expected);
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        clearQuarantine(dest);
        System.err.println("[PMCL] 已准备输入法 agent → " + dest);
        return dest;
    }

    private static void clearQuarantine(Path file) {
        try {
            Process p = new ProcessBuilder("xattr", "-d", "com.apple.quarantine", file.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }
}
