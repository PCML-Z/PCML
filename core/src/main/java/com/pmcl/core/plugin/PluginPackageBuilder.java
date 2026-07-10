package com.pmcl.core.plugin;

import com.pmcl.plugin.PluginInfo;
import com.pmcl.plugin.PluginPackageParser;
import com.pmcl.plugin.PluginPackageParser.PluginPackage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
 *   <li>Build a {@link URLClassLoader} from classes/ + lib/*.jar</li>
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

        // Extract the ZIP
        try (ZipFile zip = new ZipFile(ppkPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String entryPath = entry.getName();
                // Skip the properties file — it's only needed for single-JAR plugins;
                // the plugin.xml is the authoritative manifest for packages.
                if (entryPath.equals(PluginPackageParser.PROPERTIES_PATH)) continue;

                Path dest = targetDir.resolve(entryPath);
                // Guard against zip slip (path traversal)
                if (!dest.normalize().startsWith(targetDir.normalize())) {
                    throw new IOException("Zip entry outside target dir: " + entryPath);
                }
                Files.createDirectories(dest.getParent());
                try (InputStream is = zip.getInputStream(entry)) {
                    Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

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
            Files.walk(libDir)
                .filter(p -> p.toString().endsWith(".jar"))
                .forEach(jars::add);
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
     * Create a URLClassLoader for an extracted plugin package.
     *
     * @param extractedDir The directory where the package was extracted
     * @param pkg          The parsed package manifest
     * @param parent       The parent classloader (should be PMCL's classloader)
     * @return A new URLClassLoader with classes/ + lib/*.jar on the classpath
     * @throws IOException if classpath construction fails
     */
    public static URLClassLoader createClassLoader(Path extractedDir, PluginPackage pkg, ClassLoader parent) throws IOException {
        URL[] urls = buildClasspath(extractedDir, pkg.getLibraries());
        return new URLClassLoader(urls, parent);
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
