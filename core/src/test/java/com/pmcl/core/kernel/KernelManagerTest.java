package com.pmcl.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KernelManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void launchCommandUsesDotnetForDllAndTheFileItselfOtherwise() {
        Path dll = tempDir.resolve("Demo.dll");
        Path exe = tempDir.resolve("demo.exe");
        Path bin = tempDir.resolve("demo-bin");
        assertEquals(List.of("dotnet", dll.toAbsolutePath().normalize().toString()),
                KernelManager.launchCommand("csharp", dll));
        assertEquals(List.of(exe.toAbsolutePath().normalize().toString()),
                KernelManager.launchCommand("csharp", exe));
        assertEquals(List.of(bin.toAbsolutePath().normalize().toString()),
                KernelManager.launchCommand("rust", bin));
    }

    @Test
    void rejectsUnknownRuntimeAndZipSlip() throws Exception {
        Path folder = tempDir.resolve("bad-runtime");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("pmcl-kernel.json"), """
                {"id":"bad-runtime","name":"Bad","version":"1.0.0","runtime":"go","entry":"a","artifact":"a"}
                """);
        Files.writeString(folder.resolve("a"), "x");
        KernelManager manager = new KernelManager(tempDir.resolve("kernels"));
        KernelException rejected = assertThrows(KernelException.class, () -> manager.install(folder));
        assertEquals("invalid_runtime", rejected.code());

        Path zip = tempDir.resolve("slip.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped.txt"));
            out.write("nope".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("pmcl-kernel.json"));
            out.write("""
                    {"id":"slip","name":"Slip","version":"1.0.0","runtime":"rust","artifact":"demo"}
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        KernelException slip = assertThrows(KernelException.class, () -> manager.install(zip));
        assertEquals("invalid_entry", slip.code());
        assertFalse(Files.exists(tempDir.resolve("escaped.txt")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rustKernelStaysRunningAfterHello() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "测试需要 JDK 的 javac");
        Path classes = tempDir.resolve("hello-classes");
        Files.createDirectories(classes);
        Path hello = tempDir.resolve("HelloProcess.java");
        Files.writeString(hello, """
                public class HelloProcess {
                    public static void main(String[] args) throws Exception {
                        System.out.println("{\\"ok\\":true}");
                        System.out.flush();
                        Thread.sleep(60000);
                    }
                }
                """);
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), hello.toString()));
        Path folder = tempDir.resolve("rust-src");
        Files.createDirectories(folder);
        Path script = folder.resolve("hello.sh");
        Files.writeString(script, "#!/bin/sh\nexec java -cp '" + classes + "' HelloProcess\n");
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(folder.resolve("pmcl-kernel.json"), """
                {"id":"hello-rust","name":"Hello","version":"1.0.0","runtime":"rust","artifact":"hello.sh"}
                """);
        KernelManager manager = new KernelManager(tempDir.resolve("kernels"));
        try {
            manager.install(folder);
            KernelManager.Installed enabled = manager.setEnabled("hello-rust", true);
            assertEquals(KernelManager.HEALTH_READY, enabled.getHealth(), enabled.getDetail());
            assertTrue(enabled.isEnabled());
        } finally {
            if (!manager.list().isEmpty()) manager.remove("hello-rust");
        }
        assertTrue(manager.list().isEmpty());
    }

    @Test
    void javaKernelStartsAndStopsInsideTheLauncher() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "测试需要 JDK 的 javac");
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path source = tempDir.resolve("DemoKernel.java");
        Files.writeString(source, """
                package demo;
                import com.pmcl.plugin.kernel.KernelHost;
                import com.pmcl.plugin.kernel.LauncherKernel;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                public class DemoKernel implements LauncherKernel {
                    private KernelHost host;
                    public String id() { return "demo-kernel"; }
                    public void start(KernelHost host) {
                        this.host = host;
                        try {
                            Files.writeString(host.dataDirectory().resolve("started.txt"), "ok", StandardCharsets.UTF_8);
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }
                    public void stop() {
                        try {
                            Files.writeString(host.dataDirectory().resolve("stopped.txt"), "ok", StandardCharsets.UTF_8);
                        } catch (Exception ignored) {}
                    }
                }
                """);
        int compiled = compiler.run(null, null, null, "-d", classes.toString(),
                "-cp", System.getProperty("java.class.path"), source.toString());
        assertEquals(0, compiled, "示例内核应能和 PMCL 的接口一起编译");

        Path jar = tempDir.resolve("demo-kernel.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("pmcl-kernel.json"));
            out.write("""
                    {"id":"demo-kernel","name":"Demo","version":"1.0.0","runtime":"java","entry":"demo.DemoKernel"}
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            Path classFile = classes.resolve("demo").resolve("DemoKernel.class");
            out.putNextEntry(new JarEntry("demo/DemoKernel.class"));
            out.write(Files.readAllBytes(classFile));
            out.closeEntry();
        }

        KernelManager manager = new KernelManager(tempDir.resolve("kernels"));
        KernelManager.Installed installed = manager.install(jar);
        assertEquals("demo-kernel.jar", installed.getManifest().getArtifact());
        KernelManager.Installed enabled = manager.setEnabled("demo-kernel", true);
        assertEquals(KernelManager.HEALTH_READY, enabled.getHealth());
        Path data = tempDir.resolve("kernels").resolve("demo-kernel").resolve("data");
        assertEquals("ok", Files.readString(data.resolve("started.txt")).strip());
        manager.setEnabled("demo-kernel", false);
        assertEquals("ok", Files.readString(data.resolve("stopped.txt")).strip());
        assertEquals(KernelManager.HEALTH_DISABLED, manager.list().get(0).getHealth());
    }

    @Test
    void javaKernelStartsTheGameWithoutStayingEnabled() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "测试需要 JDK 的 javac");
        Path classes = tempDir.resolve("launch-classes");
        Files.createDirectories(classes);
        Path source = tempDir.resolve("LaunchKernel.java");
        Files.writeString(source, """
                package demo;
                import com.pmcl.plugin.kernel.KernelHost;
                import com.pmcl.plugin.kernel.LauncherKernel;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.List;
                import java.util.Map;
                public class LaunchKernel implements LauncherKernel {
                    public String id() { return "launch-kernel"; }
                    public Process launch(KernelHost host, List<String> command, Path directory,
                                          Map<String, String> environment, Path appendLog,
                                          boolean discardOutput) throws Exception {
                        Files.writeString(host.dataDirectory().resolve("launched.txt"),
                                String.join("\\n", command), StandardCharsets.UTF_8);
                        return LauncherKernel.super.launch(host, command, directory, environment, appendLog, discardOutput);
                    }
                }
                """);
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(),
                "-cp", System.getProperty("java.class.path"), source.toString()));
        Path echo = tempDir.resolve("EchoGame.java");
        Files.writeString(echo, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class EchoGame {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), System.getenv().getOrDefault("PMCL_KERNEL_MARK", ""));
                        System.out.println("game-ready");
                    }
                }
                """);
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), echo.toString()));

        Path jar = tempDir.resolve("launch-kernel.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("pmcl-kernel.json"));
            out.write("""
                    {"id":"launch-kernel","name":"Launch","version":"1.0.0","runtime":"java","entry":"demo.LaunchKernel"}
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("demo/LaunchKernel.class"));
            out.write(Files.readAllBytes(classes.resolve("demo").resolve("LaunchKernel.class")));
            out.closeEntry();
        }
        Path marker = tempDir.resolve("played.txt");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        KernelManager manager = new KernelManager(tempDir.resolve("kernels"));
        manager.install(jar);
        Process game = manager.launchGame("launch-kernel",
                List.of(javaBin, "-cp", classes.toString(), "EchoGame", marker.toString()),
                tempDir, Map.of("PMCL_KERNEL_MARK", "from-kernel"), null, false);
        String output = new String(game.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, game.waitFor());
        assertTrue(output.contains("game-ready"));
        assertEquals("from-kernel", Files.readString(marker).strip());
        assertTrue(Files.readString(tempDir.resolve("kernels/launch-kernel/data/launched.txt")).contains("EchoGame"));
        assertFalse(manager.list().get(0).isEnabled());
        assertFalse(Files.exists(tempDir.resolve("kernels/state.json")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void nativeKernelTakesTheLaunchAndCanRefuseIt() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "测试需要 JDK 的 javac");
        Path classes = tempDir.resolve("native-classes");
        Files.createDirectories(classes);
        Path shim = tempDir.resolve("LaunchShim.java");
        Files.writeString(shim, """
                import java.io.BufferedReader;
                import java.io.InputStreamReader;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Path;
                import java.util.ArrayList;
                import java.util.List;
                public class LaunchShim {
                    public static void main(String[] args) throws Exception {
                        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                        if (line == null || !line.contains("\\"op\\":\\"launch\\"")) {
                            System.err.println("{\\"ok\\":false,\\"error\\":\\"missing launch\\"}");
                            System.err.flush();
                            System.exit(2);
                        }
                        List<String> command = commandOf(line);
                        String directory = field(line, "directory");
                        String mark = field(line, "PMCL_KERNEL_MARK");
                        System.err.println("{\\"ok\\":true}");
                        System.err.flush();
                        ProcessBuilder builder = new ProcessBuilder(command);
                        if (!directory.isEmpty()) builder.directory(Path.of(directory).toFile());
                        if (!mark.isEmpty()) builder.environment().put("PMCL_KERNEL_MARK", mark);
                        builder.redirectErrorStream(true);
                        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        System.exit(builder.start().waitFor());
                    }
                    static String field(String json, String name) {
                        String key = "\\"" + name + "\\":\\"";
                        int start = json.indexOf(key);
                        if (start < 0) return "";
                        StringBuilder out = new StringBuilder();
                        boolean escape = false;
                        for (int i = start + key.length(); i < json.length(); i++) {
                            char c = json.charAt(i);
                            if (escape) { out.append(c); escape = false; continue; }
                            if (c == '\\\\') { escape = true; continue; }
                            if (c == '"') break;
                            out.append(c);
                        }
                        return out.toString();
                    }
                    static List<String> commandOf(String json) {
                        int key = json.indexOf("\\"command\\"");
                        int left = json.indexOf('[', key);
                        int right = json.indexOf(']', left);
                        List<String> command = new ArrayList<>();
                        if (left < 0 || right < 0) return command;
                        StringBuilder current = null;
                        boolean escape = false;
                        for (int i = left + 1; i < right; i++) {
                            char c = json.charAt(i);
                            if (current == null) {
                                if (c == '"') current = new StringBuilder();
                                continue;
                            }
                            if (escape) { current.append(c); escape = false; continue; }
                            if (c == '\\\\') { escape = true; continue; }
                            if (c == '"') { command.add(current.toString()); current = null; continue; }
                            current.append(c);
                        }
                        return command;
                    }
                }
                """);
        Path refuse = tempDir.resolve("RefuseLaunch.java");
        Files.writeString(refuse, """
                public class RefuseLaunch {
                    public static void main(String[] args) {
                        System.err.println("{\\"ok\\":false,\\"error\\":\\"refused\\"}");
                        System.err.flush();
                    }
                }
                """);
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), shim.toString(), refuse.toString()));
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path folder = tempDir.resolve("native-src");
        Files.createDirectories(folder);
        Path script = folder.resolve("launch.sh");
        Files.writeString(script, "#!/bin/sh\nexec '" + javaBin + "' -cp '" + classes + "' LaunchShim\n");
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(folder.resolve("pmcl-kernel.json"), """
                {"id":"launch-rust","name":"LaunchRust","version":"1.0.0","runtime":"rust","artifact":"launch.sh"}
                """);
        Path echo = tempDir.resolve("EchoGame.java");
        if (!Files.exists(echo)) {
            Files.writeString(echo, """
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    public class EchoGame {
                        public static void main(String[] args) throws Exception {
                            Files.writeString(Path.of(args[0]), System.getenv().getOrDefault("PMCL_KERNEL_MARK", ""));
                            System.out.println("game-ready");
                        }
                    }
                    """);
            assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), echo.toString()));
        } else {
            assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), echo.toString()));
        }
        KernelManager manager = new KernelManager(tempDir.resolve("native-kernels"));
        manager.install(folder);
        Path marker = tempDir.resolve("native-played.txt");
        Process game = manager.launchGame("launch-rust",
                List.of(javaBin, "-cp", classes.toString(), "EchoGame", marker.toString()),
                tempDir, Map.of("PMCL_KERNEL_MARK", "from-native"), null, false);
        String output = new String(game.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, game.waitFor(), output);
        assertTrue(output.contains("game-ready"), output);
        assertEquals("from-native", Files.readString(marker).strip());
        assertFalse(manager.list().get(0).isEnabled());

        Path refusedDir = tempDir.resolve("refused-src");
        Files.createDirectories(refusedDir);
        Path refusedScript = refusedDir.resolve("refuse.sh");
        Files.writeString(refusedScript, "#!/bin/sh\nexec '" + javaBin + "' -cp '" + classes + "' RefuseLaunch\n");
        Files.setPosixFilePermissions(refusedScript, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(refusedDir.resolve("pmcl-kernel.json"), """
                {"id":"refuse-rust","name":"Refuse","version":"1.0.0","runtime":"rust","artifact":"refuse.sh"}
                """);
        manager.install(refusedDir);
        KernelException rejected = assertThrows(KernelException.class, () -> manager.launchGame(
                "refuse-rust", List.of(javaBin, "-version"), tempDir, Map.of(), null, false));
        assertEquals("launch_failed", rejected.code());
        assertTrue(rejected.detail().contains("refused"), rejected.detail());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void helloOnlyKernelDoesNotCountAsTakingTheLaunch() throws Exception {
        Path dir = tempDir.resolve("hello-only");
        Files.createDirectories(dir);
        Path script = dir.resolve("hello.sh");
        Files.writeString(script, "#!/bin/sh\nread line\necho '{\"ok\":true}'\nexec sleep 60\n");
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(dir.resolve("pmcl-kernel.json"), """
                {"id":"hello-only","name":"Hello","version":"1.0.0","runtime":"rust","artifact":"hello.sh"}
                """);
        KernelManager manager = new KernelManager(tempDir.resolve("hello-kernels"));
        manager.install(dir);
        long start = System.nanoTime();
        KernelException rejected = assertThrows(KernelException.class, () -> manager.launchGame(
                "hello-only", List.of("/usr/bin/true"), tempDir, Map.of(), null, false));
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals("launch_unsupported", rejected.code());
        assertTrue(elapsedMillis < 5000, "waited " + elapsedMillis + "ms");
    }
}
