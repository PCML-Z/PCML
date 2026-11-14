package com.pmcl.core.dev;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 写出 ECXP 应用或 PMCL 插件的开发目录。
 * 不启动游戏，不写 preferences.json。找不到 VS Code 或 IDEA 时只返回 false。
 */
public final class DevProjects {

    public enum Kind {
        ECXP_STUDIO,
        ECXP_VSCODE,
        PLUGIN_IDEA
    }

    private static final String SDK = "dev/ecxp-sdk/";
    private static final String[] SDK_FILES = {
            "Pmcl.Ecxp.csproj",
            "PmclApp.cs",
            "PmclApis.cs",
            "build/Pmcl.Ecxp.targets"
    };

    private DevProjects() {}

    public static boolean isPlugin(Kind kind) {
        return kind == Kind.PLUGIN_IDEA;
    }

    /** 从显示名得到项目标识。插件不含点，ECXP 带 pmcl. 前缀。 */
    public static String slug(String name, boolean plugin) {
        String raw = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        StringBuilder body = new StringBuilder();
        boolean sep = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                body.append(c);
                sep = false;
            } else if (c == ' ' || c == '_' || c == '-' || c == '.' || c == '　') {
                if (body.length() > 0 && !sep) {
                    body.append('-');
                    sep = true;
                }
            }
        }
        String id = trimSep(body.toString());
        if (id.startsWith("pmcl-")) id = id.substring(5);
        id = trimSep(id);
        if (id.isEmpty()) return "";
        if (plugin) {
            if (id.length() > 32) id = trimSep(id.substring(0, 32));
            return id;
        }
        id = "pmcl." + id;
        if (id.length() > 64) id = trimSep(id.substring(0, 64));
        return id;
    }

    public static boolean validId(String id, boolean plugin) {
        if (id == null) return false;
        if (plugin) {
            return id.length() >= 3 && id.length() <= 32
                    && id.matches("^[a-z][a-z0-9-]{1,30}[a-z0-9]$")
                    && !id.contains("--");
        }
        return id.length() >= 2 && id.length() <= 64
                && id.matches("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$");
    }

    public static boolean validName(String name) {
        if (name == null) return false;
        String n = name.strip();
        return !n.isEmpty() && n.length() <= 64 && n.indexOf('\n') < 0 && n.indexOf('\r') < 0;
    }

    /**
     * 在 parent 下用 id 做文件夹名。文件夹里已有文件时抛出 message 为 exists 的 IOException。
     */
    public static Path create(Path parent, Kind kind, String name, String id) throws IOException {
        if (parent == null || kind == null || !validName(name) || !validId(id, isPlugin(kind))) {
            throw new IOException("invalid");
        }
        Path parentAbs = parent.toAbsolutePath().normalize();
        Path dest = parentAbs.resolve(id).normalize();
        if (!dest.startsWith(parentAbs) || dest.equals(parentAbs) || Files.isSymbolicLink(dest)) {
            throw new IOException("invalid");
        }
        boolean existed = Files.exists(dest);
        if (existed && !isEmpty(dest)) throw new IOException("exists");
        Files.createDirectories(dest);
        try {
            if (isPlugin(kind)) writePlugin(dest, name.strip(), id);
            else writeEcxp(dest, name.strip(), id);
        } catch (IOException e) {
            if (!existed) deleteTree(dest);
            throw e;
        }
        return dest;
    }

    /** 打开 VS Code 或 IDEA。Studio 由界面自己打开，这里返回 false。 */
    public static boolean openExternal(Kind kind, Path dir) {
        if (kind == null || kind == Kind.ECXP_STUDIO || dir == null) return false;
        String folder = dir.toAbsolutePath().normalize().toString();
        for (List<String> cmd : commands(kind, folder)) {
            try {
                Process process = new ProcessBuilder(cmd)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() == 0) return true;
            } catch (IOException ignored) {
                // 试下一个路径
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void writeEcxp(Path dest, String name, String id) throws IOException {
        String type = typeName(id);
        copySdk(dest.resolve("sdk").resolve("Pmcl.Ecxp"));
        String asm = xml(type);
        String csproj = """
                <Project Sdk="Microsoft.NET.Sdk">

                  <PropertyGroup>
                    <OutputType>WinExe</OutputType>
                    <TargetFramework>net11.0</TargetFramework>
                    <TargetFramework Condition="!$([MSBuild]::VersionGreaterThanOrEquals('$(NETCoreSdkVersion)', '11.0'))">net10.0</TargetFramework>
                    <ImplicitUsings>enable</ImplicitUsings>
                    <Nullable>enable</Nullable>
                    <LangVersion>latest</LangVersion>
                    <AssemblyName>%s</AssemblyName>
                    <RootNamespace>Pmcl.%s</RootNamespace>
                    <Version>1.0.0</Version>
                    <SatelliteResourceLanguages>en</SatelliteResourceLanguages>

                    <PmclAppId>%s</PmclAppId>
                    <PmclAppName>%s</PmclAppName>
                    <PmclAppAuthor>%s</PmclAppAuthor>
                    <PmclAppDescription>%s</PmclAppDescription>
                  </PropertyGroup>

                  <ItemGroup>
                    <PackageReference Include="Avalonia" Version="11.3.22" />
                    <PackageReference Include="Avalonia.Desktop" Version="11.3.22" />
                    <PackageReference Include="Avalonia.Themes.Fluent" Version="11.3.22" />
                  </ItemGroup>

                  <ItemGroup>
                    <ProjectReference Include="sdk/Pmcl.Ecxp/Pmcl.Ecxp.csproj" />
                  </ItemGroup>

                  <Import Project="sdk/Pmcl.Ecxp/build/Pmcl.Ecxp.targets" />

                </Project>
                """.formatted(asm, asm, xml(id), xml(name), xml(author()), xml("ECXP application for PMCL."));
        write(dest.resolve(type + ".csproj"), csproj);
        write(dest.resolve("Program.cs"), """
                using Avalonia;

                namespace Pmcl.%s;

                internal static class Program
                {
                    [STAThread]
                    public static void Main(string[] args) =>
                        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);

                    public static AppBuilder BuildAvaloniaApp() =>
                        AppBuilder.Configure<App>().UsePlatformDetect().LogToTrace();
                }
                """.formatted(type));
        write(dest.resolve("App.cs"), """
                using Avalonia;
                using Avalonia.Controls.ApplicationLifetimes;
                using Avalonia.Styling;
                using Avalonia.Themes.Fluent;
                using Pmcl.Ecxp;

                namespace Pmcl.%s;

                public sealed class App : Application
                {
                    public override void Initialize()
                    {
                        Styles.Add(new FluentTheme());
                        RequestedThemeVariant = PmclApp.Theme == "light" ? ThemeVariant.Light : ThemeVariant.Dark;
                    }

                    public override void OnFrameworkInitializationCompleted()
                    {
                        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
                        {
                            desktop.MainWindow = new MainWindow();
                        }
                        base.OnFrameworkInitializationCompleted();
                    }
                }
                """.formatted(type));
        write(dest.resolve("MainWindow.cs"), """
                using Avalonia.Controls;
                using Avalonia.Layout;
                using Pmcl.Ecxp;

                namespace Pmcl.%s;

                public sealed class MainWindow : Window
                {
                    private readonly TextBlock _status = new() { Text = "…" };
                    private PmclApp? _app;

                    public MainWindow()
                    {
                        Title = "%s";
                        Width = 480;
                        Height = 280;
                        var notify = new Button { Content = "Notify", HorizontalAlignment = HorizontalAlignment.Left };
                        notify.Click += async (_, _) =>
                        {
                            if (_app != null) await _app.Ui.NotifyAsync(Title, "Hello from ECXP");
                        };
                        Content = new StackPanel
                        {
                            Margin = new Avalonia.Thickness(24),
                            Spacing = 12,
                            Children = { new TextBlock { Text = Title, FontSize = 20 }, _status, notify }
                        };
                        Opened += async (_, _) => await Connect();
                        Closed += async (_, _) =>
                        {
                            if (_app != null) await _app.DisposeAsync();
                        };
                    }

                    private async System.Threading.Tasks.Task Connect()
                    {
                        if (!PmclApp.IsHosted)
                        {
                            _status.Text = "Start this app from PMCL after dotnet publish.";
                            return;
                        }
                        try
                        {
                            _app = await PmclApp.ConnectAsync();
                            _status.Text = _app.Info.Launcher + " " + _app.Info.LauncherVersion;
                        }
                        catch (System.Exception e)
                        {
                            _status.Text = e.Message;
                        }
                    }
                }
                """.formatted(type, csharp(name)));
        write(dest.resolve(".vscode").resolve("tasks.json"), """
                {
                  "version": "2.0.0",
                  "tasks": [
                    {
                      "label": "build",
                      "type": "process",
                      "command": "dotnet",
                      "args": ["build"],
                      "group": { "kind": "build", "isDefault": true },
                      "problemMatcher": "$msCompile"
                    },
                    {
                      "label": "publish",
                      "type": "process",
                      "command": "dotnet",
                      "args": ["publish", "-c", "Release"],
                      "problemMatcher": "$msCompile"
                    }
                  ]
                }
                """);
        write(dest.resolve(".vscode").resolve("extensions.json"), """
                {
                  "recommendations": ["ms-dotnettools.csharp"]
                }
                """);
        write(dest.resolve(".gitignore"), "bin/\nobj/\n.vs/\n");
    }

    private static void writePlugin(Path dest, String name, String id) throws IOException {
        String className = pluginClass(id);
        String main = "pmcl.plugins.app." + className;
        write(dest.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
                        gradlePluginPortal()
                        mavenCentral()
                        google()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        maven { url = uri("https://maven.aliyun.com/repository/public") }
                        mavenCentral()
                        google()
                    }
                }

                rootProject.name = "%s"
                """.formatted(gradleString(id)));
        write(dest.resolve("build.gradle.kts"), """
                plugins {
                    kotlin("jvm") version "2.0.21"
                }

                java {
                    toolchain {
                        languageVersion.set(JavaLanguageVersion.of(21))
                    }
                }

                dependencies {
                    compileOnly(files("libs/plugin-api.jar"))
                    compileOnly("org.jetbrains.compose.runtime:runtime:1.7.0")
                }
                """);
        write(dest.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx1g\nkotlin.code.style=official\n");
        write(dest.resolve(".gitignore"), ".gradle/\nbuild/\n.idea/\n");
        writePluginApi(dest.resolve("libs").resolve("plugin-api.jar"));
        String props = "plugin.id=" + prop(id) + "\n"
                + "plugin.name=" + prop(name) + "\n"
                + "plugin.version=1.0.0\n"
                + "plugin.author=" + prop(author()) + "\n"
                + "plugin.description=PMCL plugin.\n"
                + "plugin.api-version=1.8\n"
                + "plugin.main-class=" + main + "\n"
                + "plugin.license=MIT\n";
        write(dest.resolve("src/main/resources/META-INF/pmcl-plugin.properties"), props);
        write(dest.resolve("src/main/kotlin/pmcl/plugins/app/" + className + ".kt"), """
                package pmcl.plugins.app

                import com.pmcl.plugin.PmclPlugin
                import com.pmcl.plugin.PluginContext

                class %s : PmclPlugin {
                    override val pluginId = "%s"

                    override fun onEnable(ctx: PluginContext) {
                        ctx.registerCommand("hello", "Say hello") { args ->
                            val who = args.firstOrNull() ?: "World"
                            "Hello, $who"
                        }
                    }
                }
                """.formatted(className, kotlinString(id)));
    }

    private static void copySdk(Path dest) throws IOException {
        ClassLoader loader = DevProjects.class.getClassLoader();
        for (String rel : SDK_FILES) {
            String resource = SDK + rel;
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) throw new IOException("sdk");
                Path file = dest.resolve(rel);
                Files.createDirectories(file.getParent());
                Files.copy(in, file);
            }
        }
    }

    private static void writePluginApi(Path dest) throws IOException {
        CodeSource source = com.pmcl.plugin.PmclPlugin.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) throw new IOException("api");
        Path from;
        try {
            from = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException("api", e);
        }
        Files.createDirectories(dest.getParent());
        int written = 0;
        try (OutputStream out = Files.newOutputStream(dest); JarOutputStream jar = new JarOutputStream(out)) {
            if (Files.isDirectory(from)) written = copyTreePrefix(from, jar);
            else if (Files.isRegularFile(from)) written = copyJarPrefix(from, jar);
            else throw new IOException("api");
        }
        if (written == 0) {
            Files.deleteIfExists(dest);
            throw new IOException("api");
        }
    }

    private static int copyTreePrefix(Path root, JarOutputStream jar) throws IOException {
        int count = 0;
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (!rel.startsWith("com/pmcl/plugin/")) continue;
                jar.putNextEntry(new JarEntry(rel));
                Files.copy(file, jar);
                jar.closeEntry();
                count++;
            }
        }
        return count;
    }

    private static int copyJarPrefix(Path jarFile, JarOutputStream jar) throws IOException {
        int count = 0;
        try (JarFile source = new JarFile(jarFile.toFile())) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("com/pmcl/plugin/")) continue;
                jar.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream in = source.getInputStream(entry)) {
                    in.transferTo(jar);
                }
                jar.closeEntry();
                count++;
            }
        }
        return count;
    }

    static List<List<String>> commands(Kind kind, String folder) {
        boolean win = os().contains("win");
        boolean mac = os().contains("mac");
        List<List<String>> out = new ArrayList<>();
        if (kind == Kind.ECXP_VSCODE) {
            addTool(out, "code", folder);
            if (mac) out.add(List.of("open", "-a", "Visual Studio Code", folder));
            if (win) {
                addIfFile(out, Path.of(env("LOCALAPPDATA"), "Programs", "Microsoft VS Code", "Code.exe"), folder);
                addIfFile(out, Path.of("C:\\Program Files\\Microsoft VS Code\\Code.exe"), folder);
            }
        } else if (kind == Kind.PLUGIN_IDEA) {
            addTool(out, "idea", folder);
            addTool(out, "idea64", folder);
            if (mac) {
                out.add(List.of("open", "-a", "IntelliJ IDEA", folder));
                out.add(List.of("open", "-a", "IntelliJ IDEA Ultimate", folder));
                out.add(List.of("open", "-a", "IntelliJ IDEA Community Edition", folder));
            }
            if (win) addIdeaWindows(out, folder);
        }
        return out;
    }

    private static void addTool(List<List<String>> out, String name, String folder) {
        String found = which(name);
        if (found != null) out.add(List.of(found, folder));
    }

    private static void addIfFile(List<List<String>> out, Path file, String folder) {
        if (file != null && Files.isRegularFile(file)) out.add(List.of(file.toString(), folder));
    }

    private static void addIdeaWindows(List<List<String>> out, String folder) {
        Path root = Path.of("C:\\Program Files\\JetBrains");
        if (!Files.isDirectory(root)) return;
        try (var list = Files.list(root)) {
            for (Path dir : list.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (name.startsWith("IntelliJ IDEA")) addIfFile(out, dir.resolve("bin").resolve("idea64.exe"), folder);
            }
        } catch (IOException ignored) {
            // 没有安装目录就继续试别的启动方式
        }
    }

    private static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        boolean win = os().contains("win");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path base = Path.of(dir);
            Path plain = base.resolve(name);
            if (Files.isRegularFile(plain)) return plain.toString();
            if (win) {
                Path exe = base.resolve(name + ".exe");
                Path cmd = base.resolve(name + ".cmd");
                if (Files.isRegularFile(exe)) return exe.toString();
                if (Files.isRegularFile(cmd)) return cmd.toString();
            }
        }
        return null;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value;
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private static String typeName(String id) {
        String body = id.startsWith("pmcl.") ? id.substring(5) : id;
        String name = pascal(body);
        return name.isEmpty() ? "App" : name;
    }

    private static String pluginClass(String id) {
        String name = pascal(id);
        if (name.isEmpty()) name = "App";
        if (!name.endsWith("Plugin")) name = name + "Plugin";
        return name;
    }

    private static String pascal(String raw) {
        StringBuilder out = new StringBuilder();
        boolean up = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '-' || c == '_' || c == '.') {
                up = true;
                continue;
            }
            if (!Character.isLetterOrDigit(c)) continue;
            if (out.length() == 0 && Character.isDigit(c)) out.append('N');
            out.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return out.toString();
    }

    private static String trimSep(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '-' || value.charAt(start) == '.')) start++;
        while (end > start && (value.charAt(end - 1) == '-' || value.charAt(end - 1) == '.')) end--;
        return value.substring(start, end);
    }

    private static boolean isEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var list = Files.list(dir)) {
            return list.findAny().isEmpty();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String author() {
        String value = System.getProperty("user.name", "");
        if (value == null) value = "";
        value = value.strip().replace('\r', ' ').replace('\n', ' ');
        if (value.isEmpty()) return "PMCL";
        if (value.length() > 64) value = value.substring(0, 64).strip();
        return value.isEmpty() ? "PMCL" : value;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String csharp(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    private static String prop(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "");
    }

    private static String gradleString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String kotlinString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }
}
