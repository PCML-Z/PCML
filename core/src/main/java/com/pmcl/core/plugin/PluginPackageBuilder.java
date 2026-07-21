package com.pmcl.core.plugin;

import com.pmcl.plugin.PluginInfo;
import com.pmcl.plugin.PluginPackageParser;
import com.pmcl.plugin.PluginPackageParser.PluginPackage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts and assembles a plugin package (.ppk) into a loadable plugin.
 *
 * <h3>Package Structure (after extraction)</h3>
 * <pre>
 * ~/.pmcl/plugins/&lt;pluginId&gt;/
 *   ├── plugin.xml          # Manifest copy
 *   ├── classes/            # Compiled .class files (required at runtime)
 *   ├── lib/                # Dependency JARs (optional)
 *   ├── resources/          # Plugin resources (optional)
 *   └── src/                # Source files (kt + java, for reference/recompilation)
 * </pre>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Parse and validate the .ppk via {@link PluginPackageParser}</li>
 *   <li>Extract the ZIP to a per-plugin directory under ~/.pmcl/plugins/&lt;id&gt;/</li>
 *   <li>Build a {@link com.pmcl.core.plugin.PluginIsolatingClassLoader} from classes/ + lib/*.jar</li>
 *   <li>Load the main class and instantiate the plugin</li>
 * </ol>
 *
 * The .ppk package must contain compiled bytecode in <code>classes/</code>.
 * Source files in <code>src/kt/</code> and <code>src/java/</code> are kept
 * for documentation and optional recompilation, but are NOT compiled at install time.
 */
public final class PluginPackageBuilder {

    /** Subdirectory inside the .ppk containing compiled .class files. */
    public static final String CLASSES_DIR = "classes/";

    private PluginPackageBuilder() {}

    /**
     * Extract a .ppk package to the target directory.
     * The target directory will contain: plugin.xml, classes/, lib/, resources/, src/
     * <p>
     * S22 安全修复：使用 {@link com.pmcl.core.util.SafeZipExtractor} 统一防护
     * ZipSlip（路径穿越）与 ZipBomb（解压炸弹）。
     *
     * @param ppkPath    Path to the .ppk file
     * @param targetDir  Directory to extract into (will be created if not exists)
     * @return the parsed PluginPackage manifest
     * @throws IOException if extraction fails
     * @throws IllegalArgumentException if the package violates the format spec
     */
    public static PluginPackage extract(Path ppkPath, Path targetDir) throws IOException {
        // Parse and validate the package first (will throw if invalid)
        PluginPackage pkg = PluginPackageParser.parse(ppkPath);

        // Prepare target directory
        Files.createDirectories(targetDir);
        // Clean target if it already exists (re-install scenario)
        deleteDirectoryContents(targetDir);

        // S22 安全修复：使用 SafeZipExtractor 防护 ZipSlip 与 ZipBomb
        // 跳过 META-INF/pmcl-plugin.properties（仅 single-JAR 需要，.ppk 以 plugin.xml 为准）
        com.pmcl.core.util.SafeZipExtractor.extractSafely(ppkPath, targetDir,
                entry -> !entry.getName().equals(PluginPackageParser.PROPERTIES_PATH));

        return pkg;
    }

    /**
     * Build a classpath URL array for an extracted plugin package.
     * Includes the classes/ directory and all .jar files in lib/.
     *
     * @param extractedDir The directory where the package was extracted
     * @param libraries    Library declarations from the manifest (for validation)
     * @return URL array for classloader construction
     * @throws IOException if paths cannot be resolved
     */
    public static URL[] buildClasspath(Path extractedDir, List<PluginPackageParser.LibraryRef> libraries) throws IOException {
        List<URL> urls = new ArrayList<>();

        // Add classes/ directory
        Path classesDir = extractedDir.resolve(CLASSES_DIR);
        if (Files.isDirectory(classesDir)) {
            urls.add(classesDir.toUri().toURL());
        } else {
            throw new IOException(
                "Plugin package missing 'classes/' directory. " +
                "A .ppk package must contain compiled bytecode in classes/. " +
                "Source files in src/kt/ and src/java/ must be compiled before packaging.");
        }

        // Add lib/*.jar
        Path libDir = extractedDir.resolve("lib");
        if (Files.isDirectory(libDir)) {
            List<Path> jars = new ArrayList<>();
            try (var stream = Files.walk(libDir)) {
                stream.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .forEach(jars::add);
            }
            for (Path jar : jars) {
                urls.add(jar.toUri().toURL());
            }
        }

        // Validate that all declared libraries exist
        for (PluginPackageParser.LibraryRef lib : libraries) {
            Path libPath = extractedDir.resolve(lib.getPath());
            if (!Files.exists(libPath)) {
                throw new IOException(
                    "Declared library '" + lib.getPath() + "' not found in extracted package.");
            }
        }

        return urls.toArray(new URL[0]);
    }

    /**
     * Create a PluginIsolatingClassLoader for an extracted plugin package.
     * 使用隔离 ClassLoader 阻止插件直接加载 com.pmcl.core.* 内部类。
     *
     * @param extractedDir The directory where the package was extracted
     * @param pkg          The parsed package manifest
     * @param parent       The parent classloader (should be PMCL's classloader)
     * @return A new PluginIsolatingClassLoader with classes/ + lib/*.jar on the classpath
     * @throws IOException if classpath construction fails
     */
    public static PluginIsolatingClassLoader createClassLoader(Path extractedDir, PluginPackage pkg, ClassLoader parent) throws IOException {
        URL[] urls = buildClasspath(extractedDir, pkg.getLibraries());
        return new PluginIsolatingClassLoader(pkg.getInfo().getId(), urls, parent);
    }

    /**
     * Validate that an extracted package has the required runtime structure.
     * Throws if classes/ is missing (source-only packages are not loadable).
     */
    public static void validateRuntimeStructure(Path extractedDir) throws IOException {
        Path classesDir = extractedDir.resolve(CLASSES_DIR);
        if (!Files.isDirectory(classesDir)) {
            throw new IOException(
                "Plugin package is missing compiled bytecode. " +
                "The 'classes/' directory was not found. " +
                "A .ppk must contain pre-compiled .class files; " +
                "source files alone cannot be loaded. " +
                "Compile the Kotlin/Java sources with the plugin-api on the classpath " +
                "before packaging the .ppk.");
        }
        // Verify classes/ is not empty
        try (var stream = Files.list(classesDir)) {
            if (!stream.findAny().isPresent()) {
                throw new IOException(
                    "Plugin package 'classes/' directory is empty. " +
                    "Compile the sources before packaging.");
            }
        }
    }

    /**
     * Get the plugin's data directory (where plugin.xml and extracted files live).
     * This is separate from the plugin's persistent data dir managed by PluginContext.
     */
    public static Path getPackageDir(Path pluginsDir, String pluginId) {
        return pluginsDir.resolve(pluginId);
    }

    // ==================== Internal ====================

    private static void deleteDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            // Delete in reverse order (files before dirs)
            stream.sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        if (!p.equals(dir)) Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Non-fatal
                    }
                });
        }
    }
}
