package com.pmcl.core.automation;

import com.pmcl.core.i18n.I18n;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 编译并运行用户的自动化命令。
 * Kotlin / Java 由本进程里的编译器产出 class，再交给独立的 java 进程。
 * C、C++、Go 只执行用户已经编译好的文件，不调用编译器，也不经过 shell。
 */
public final class AutomationRunner {

    private static final Object LOCK = new Object();
    private static final int OUTPUT_LIMIT = 8000;

    private AutomationRunner() {}

    public static final class Result {
        public final String name;
        public final int exitCode;
        public final String output;

        public Result(String name, int exitCode, String output) {
            this.name = name == null ? "" : name;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public String summary() {
            String head = I18n.t("settings.automation.summary", name, exitCode);
            if (output.isBlank()) return head;
            return head + "\n" + output;
        }
    }

    public static void runTrigger(com.pmcl.core.preferences.Preferences preferences, Path workDir,
                                  String event, String versionId, String gameDir, Integer exitCode,
                                  Consumer<String> log) throws InterruptedException {
        if (preferences == null || event == null) return;
        for (AutomationCommand command : preferences.getAutomationCommands()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!command.isEnabled() || !event.equals(command.getTrigger())) continue;
            Result result = run(command, workDir, event, versionId, gameDir, exitCode);
            if (log != null) log.accept(result.summary());
        }
    }

    public static Result run(AutomationCommand command, Path workDir, String event,
                             String versionId, String gameDir, Integer exitCode) throws InterruptedException {
        synchronized (LOCK) {
            try {
                return runLocked(command, workDir, event, versionId, gameDir, exitCode);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return new Result(command == null ? "" : command.getName(), -1, message);
            }
        }
    }

    private static Result runLocked(AutomationCommand command, Path workDir, String event,
                                    String versionId, String gameDir, Integer exitCode) throws Exception {
        if (command == null) return new Result("", -1, I18n.t("settings.automation.failed", "empty"));
        if (workDir == null) return new Result(command.getName(), -1, I18n.t("settings.automation.failed", "workDir"));
        Path root = workDir.toAbsolutePath().normalize();
        Path dir = root.resolve("automation").resolve(command.getId()).normalize();
        if (!dir.startsWith(root.resolve("automation"))) {
            return new Result(command.getName(), -1, I18n.t("settings.automation.failed", "path"));
        }
        Files.createDirectories(dir);
        List<String> cmd;
        if (command.isJvm()) {
            String sourceError = validateSource(command);
            if (sourceError != null) return new Result(command.getName(), -1, sourceError);
            deleteClasses(dir);
            if (AutomationCommand.JAVA.equals(command.getLanguage())) {
                String compiled = compileJava(dir, command.getSource());
                if (compiled != null) return new Result(command.getName(), -1, compiled);
                cmd = javaCommand(dir, "Main", command.getArgs());
            } else {
                String compiled = compileKotlin(dir, command.getSource());
                if (compiled != null) return new Result(command.getName(), -1, compiled);
                cmd = javaCommand(dir, "MainKt", command.getArgs());
            }
        } else {
            if (command.getExecutable().isEmpty()) {
                return new Result(command.getName(), -1, I18n.t("settings.automation.no_executable"));
            }
            Path exe = Path.of(command.getExecutable()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(exe)) {
                return new Result(command.getName(), -1, I18n.t("settings.automation.no_executable"));
            }
            cmd = new ArrayList<>();
            cmd.add(exe.toString());
            cmd.addAll(command.getArgs());
        }
        return execute(command.getName(), cmd, dir, event, versionId, gameDir, exitCode, workDir, timeoutSeconds(event));
    }

    private static String validateSource(AutomationCommand command) {
        String source = command.getSource();
        if (source.isBlank()) return I18n.t("settings.automation.empty_source");
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("package\t")) {
                return I18n.t("settings.automation.no_package");
            }
        }
        if (AutomationCommand.JAVA.equals(command.getLanguage())) {
            if (!source.contains("class Main")) return I18n.t("settings.automation.need_java_main");
        } else if (!source.contains("fun main")) {
            return I18n.t("settings.automation.need_kotlin_main");
        }
        return null;
    }

    private static String compileJava(Path dir, String source) throws IOException, InterruptedException {
        Path file = dir.resolve("Main.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler != null) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = compiler.run(null, err, err, "-encoding", "UTF-8", "-d", dir.toString(), file.toString());
            if (code != 0) return clip(err.toString(StandardCharsets.UTF_8));
            return null;
        }
        Path javac = javacBinary();
        if (javac == null) return I18n.t("settings.automation.no_javac");
        Result result = execute("javac", List.of(javac.toString(), "-encoding", "UTF-8", "-d", dir.toString(), file.toString()),
                dir, "", "", "", null, dir, 30);
        if (result.exitCode != 0) return result.output.isBlank() ? I18n.t("settings.automation.no_javac") : result.output;
        return null;
    }

    private static String compileKotlin(Path dir, String source) throws Exception {
        Path file = dir.resolve("Main.kt");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        Class<?> compilerClass;
        try {
            compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        } catch (ClassNotFoundException e) {
            return I18n.t("settings.automation.no_kotlin");
        }
        Object compiler = compilerClass.getDeclaredConstructor().newInstance();
        Method exec = null;
        for (Method method : compilerClass.getMethods()) {
            if (!"exec".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0] == PrintStream.class && params[1].isArray()) {
                exec = method;
                break;
            }
        }
        if (exec == null) return I18n.t("settings.automation.no_kotlin");
        String stdlib = stdlibPath();
        if (stdlib == null) return I18n.t("settings.automation.no_kotlin");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(err, true, StandardCharsets.UTF_8);
        Object code = exec.invoke(compiler, new Object[]{stream, new String[]{
                file.toString(),
                "-d", dir.toString(),
                "-classpath", stdlib,
                "-jvm-target", "17"
        }});
        stream.flush();
        if (code == null || !"OK".equals(String.valueOf(code))) {
            String message = clip(err.toString(StandardCharsets.UTF_8));
            return message.isBlank() ? String.valueOf(code) : message;
        }
        return null;
    }

    private static List<String> javaCommand(Path dir, String mainClass, List<String> args) {
        String javaBin = Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java")
                .toString();
        String classpath = dir.toString();
        if ("MainKt".equals(mainClass)) {
            String stdlib = stdlibPath();
            if (stdlib != null) classpath = dir + java.io.File.pathSeparator + stdlib;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        cmd.addAll(args);
        return cmd;
    }

    private static Result execute(String name, List<String> cmd, Path dir, String event,
                                  String versionId, String gameDir, Integer exitCode,
                                  Path workDir, int timeoutSeconds) throws InterruptedException, IOException {
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        var env = builder.environment();
        env.put("PMCL_EVENT", safe(event));
        env.put("PMCL_VERSION_ID", safe(versionId));
        env.put("PMCL_GAME_DIR", safe(gameDir));
        env.put("PMCL_WORK_DIR", workDir.toAbsolutePath().normalize().toString());
        if (exitCode != null) env.put("PMCL_EXIT_CODE", Integer.toString(exitCode));
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(process.getInputStream(), captured), "pmcl-automation");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                process.destroyForcibly();
                reader.join(1000);
                throw new InterruptedException();
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                process.destroyForcibly();
                reader.join(1000);
                return new Result(name, -1, I18n.t("settings.automation.timeout"));
            }
            if (process.waitFor(Math.min(left, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS)) break;
        }
        reader.join(2000);
        String output = clip(captured.toString(StandardCharsets.UTF_8));
        return new Result(name, process.exitValue(), output);
    }

    private static void drain(InputStream in, ByteArrayOutputStream captured) {
        try {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (captured.size() < OUTPUT_LIMIT) {
                    captured.write(buf, 0, Math.min(n, OUTPUT_LIMIT - captured.size()));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void deleteClasses(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (name.endsWith(".class")) Files.deleteIfExists(path);
            }
        }
    }

    private static Path javacBinary() {
        String name = isWindows() ? "javac.exe" : "javac";
        Path home = Path.of(System.getProperty("java.home", ""));
        Path direct = home.resolve("bin").resolve(name);
        if (Files.isRegularFile(direct)) return direct;
        if (home.getParent() != null) {
            Path sibling = home.getParent().resolve("bin").resolve(name);
            if (Files.isRegularFile(sibling)) return sibling;
        }
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.isBlank()) {
            Path fromEnv = Path.of(env).resolve("bin").resolve(name);
            if (Files.isRegularFile(fromEnv)) return fromEnv;
        }
        return null;
    }

    private static String stdlibPath() {
        try {
            var source = kotlin.Unit.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                return Path.of(source.getLocation().toURI()).toString();
            }
        } catch (Exception ignored) {
        }
        String classpath = System.getProperty("java.class.path", "");
        for (String part : classpath.split(java.io.File.pathSeparator)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.contains("kotlin-stdlib") && !lower.contains("kotlin-stdlib-common")) return part;
        }
        return null;
    }

    private static int timeoutSeconds(String event) {
        if (AutomationCommand.TRIGGER_BEFORE_GAME.equals(event)
                || AutomationCommand.TRIGGER_LAUNCHER_START.equals(event)) return 20;
        return 60;
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("\n", "").replace("\r", "").replace("\0", "");
    }

    private static String clip(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= OUTPUT_LIMIT) return trimmed;
        return trimmed.substring(0, OUTPUT_LIMIT);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
