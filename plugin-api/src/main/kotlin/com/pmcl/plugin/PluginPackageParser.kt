package com.pmcl.plugin

import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Parser and strict format specification for PMCL Plugin Packages (.ppk).
 *
 * ## Plugin Package Format (.ppk)
 *
 * A `.ppk` file is a **ZIP archive** with the following strict directory structure:
 *
 * ```
 * my-plugin-1.0.0.ppk
 * ├── plugin.xml              # REQUIRED — manifest (info, sources, versions)
 * ├── src/
 * │   ├── kt/                 # Kotlin source files
 * │   │   └── com/example/
 * │   │       └── MyPlugin.kt
 * │   └── java/               # Java source files (optional)
 * │       └── com/example/
 * │           └── Helper.java
 * ├── resources/              # Plugin resources (optional)
 * │   ├── config.json
 * │   └── icon.png
 * ├── lib/                    # Dependency JARs (optional)
 * │   └── gson-2.10.jar
 * └── META-INF/
 *     └── pmcl-plugin.properties  # REQUIRED — same descriptor as single-JAR plugins
 * ```
 *
 * ## plugin.xml Structure
 *
 * The `plugin.xml` is the **information and version control** manifest.
 * It uses XML for structured, extensible metadata.
 *
 * ```xml
 * <?xml version="1.0" encoding="UTF-8"?>
 * <pmcl-plugin-package
 *     xmlns="https://pmcl.dev/plugin"
 *     format-version="1.0">
 *
 *     <!-- Plugin identity -->
 *     <info>
 *         <id>my-awesome-plugin</id>
 *         <name>My Awesome Plugin</name>
 *         <version>1.0.0</version>
 *         <author>Author Name</author>
 *         <description>Does awesome things</description>
 *         <api-version>1.0</api-version>
 *         <main-class>com.example.MyPlugin</main-class>
 *         <website>https://example.com/my-plugin</website>
 *         <license>MIT</license>
 *     </info>
 *
 *     <!-- Source file declarations (for documentation & recompilation) -->
 *     <sources>
 *         <kotlin>
 *             <file path="src/kt/com/example/MyPlugin.kt" main="true"/>
 *             <file path="src/kt/com/example/Util.kt"/>
 *         </kotlin>
 *         <java>
 *             <file path="src/java/com/example/Helper.java"/>
 *         </java>
 *     </sources>
 *
 *     <!-- Plugin dependencies (other plugin IDs) -->
 *     <dependencies>
 *         <dependency id="other-plugin" version=">=1.0.0"/>
 *         <dependency id="utility-lib" version="*"/>
 *     </dependencies>
 *
 *     <!-- Library JARs included in lib/ -->
 *     <libraries>
 *         <library path="lib/gson-2.10.jar"/>
 *     </libraries>
 *
 *     <!-- Version history / changelog -->
 *     <versions>
 *         <version number="1.0.0" date="2026-07-10" author="Author Name">
 *             Initial release.
 *         </version>
 *         <version number="0.9.0" date="2026-06-15" author="Author Name">
 *             Beta release.
 *         </version>
 *     </versions>
 *
 *     <!-- Resources included in the package -->
 *     <resources>
 *         <resource path="resources/config.json"/>
 *         <resource path="resources/icon.png"/>
 *     </resources>
 *
 * </pmcl-plugin-package>
 * ```
 *
 * ## Strict Rules
 *
 * 1. **plugin.xml** must be at the ZIP root, encoded as UTF-8
 * 2. **format-version** must be "1.0"
 * 3. **`<info>`** section: all required fields from [PluginInfo] must be present
 * 4. **`<sources>`**: at least one Kotlin source file with `main="true"` must exist
 * 5. **`<sources><kotlin><file>`**: `path` must start with `src/kt/` and end with `.kt`
 * 6. **`<sources><java><file>`**: `path` must start with `src/java/` and end with `.java`
 * 7. **`<dependencies><dependency>`**: `id` must match plugin ID rules, `version` is a version spec
 * 8. **`<libraries><library>`**: `path` must start with `lib/` and end with `.jar`
 * 9. **`<resources><resource>`**: `path` must start with `resources/`
 * 10. **`<versions>`**: at least one `<version>` entry; one must match the `<info><version>`
 * 11. **Exactly one** Kotlin file must have `main="true"`
 * 12. **No duplicate** source/library/resource paths
 * 13. All declared paths must exist in the ZIP archive
 */
object PluginPackageParser {

    const val PACKAGE_FORMAT_VERSION = "1.0"
    const val PLUGIN_XML_PATH = "plugin.xml"
    const val PROPERTIES_PATH = "META-INF/pmcl-plugin.properties"

    // ==================== Data Classes ====================

    /** A declared source file in the package. */
    data class SourceFile(
        val path: String,
        val isMain: Boolean = false,
        val language: Language
    ) {
        enum class Language { KOTLIN, JAVA }
    }

    /** A plugin dependency declaration. */
    data class PackageDependency(
        val id: String,
        val versionSpec: String  // e.g. ">=1.0.0", "*", "1.2.0"
    )

    /** A library JAR dependency. */
    data class LibraryRef(val path: String)

    /** A version history entry. */
    data class VersionEntry(
        val number: String,
        val date: String,
        val author: String,
        val changelog: String
    )

    /** A resource file declaration. */
    data class ResourceRef(val path: String)

    /** Fully parsed plugin package manifest. */
    data class PluginPackage(
        val formatVersion: String,
        val info: PluginInfo,
        val sources: List<SourceFile>,
        val dependencies: List<PackageDependency>,
        val libraries: List<LibraryRef>,
        val versions: List<VersionEntry>,
        val resources: List<ResourceRef>
    ) {
        /** The main Kotlin source file (the one with main="true"). */
        val mainSource: SourceFile
            get() = sources.first { it.isMain }

        /** All Kotlin source files. */
        val kotlinSources: List<SourceFile>
            get() = sources.filter { it.language == SourceFile.Language.KOTLIN }

        /** All Java source files. */
        val javaSources: List<SourceFile>
            get() = sources.filter { it.language == SourceFile.Language.JAVA }
    }

    // ==================== Parsing API ====================

    /**
     * Parse a .ppk plugin package file.
     * @param ppkPath Path to the .ppk ZIP file
     * @return the parsed [PluginPackage]
     * @throws IllegalArgumentException if the package violates the format spec
     * @throws java.io.IOException if the file cannot be read
     */
    @JvmStatic
    fun parse(ppkPath: Path): PluginPackage {
        ZipFile(ppkPath.toFile()).use { zip ->
            val xmlEntry = zip.getEntry(PLUGIN_XML_PATH)
                ?: throw IllegalArgumentException(
                    "Missing '$PLUGIN_XML_PATH' at package root. " +
                    "A .ppk package must contain this manifest file.")

            val xmlBytes = zip.getInputStream(xmlEntry).use { it.readAllBytes() }
            val xml = String(xmlBytes, Charsets.UTF_8)

            val pkg = parseXml(xml)

            // Cross-validate: all declared paths must exist in the ZIP
            validatePathsExist(zip, pkg)

            return pkg
        }
    }

    /**
     * Parse plugin.xml XML string into a [PluginPackage].
     * Does NOT validate that declared file paths exist (use [parse] for that).
     */
    @JvmStatic
    fun parseXml(xml: String): PluginPackage {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        // XXE protection
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (e: Exception) {
            // Features may not be available on all parsers; non-fatal
        }

        val doc = factory.newDocumentBuilder().parse(InputSource(xml.byteInputStream(Charsets.UTF_8)))
        return parseDocument(doc)
    }

    // ==================== Internal Parsing ====================

    private fun parseDocument(doc: Document): PluginPackage {
        val root = doc.documentElement
        if (root.tagName != "pmcl-plugin-package") {
            throw IllegalArgumentException(
                "Root element must be <pmcl-plugin-package>, got <${root.tagName}>")
        }

        val formatVersion = root.getAttribute("format-version")
        if (formatVersion != PACKAGE_FORMAT_VERSION) {
            throw IllegalArgumentException(
                "Unsupported package format-version '$formatVersion'. " +
                "This PMCL supports format-version '$PACKAGE_FORMAT_VERSION'.")
        }

        // Parse <info>
        val infoElem = root.childElement("info")
        val info = parseInfo(infoElem)

        // Parse <sources>
        val sources = parseSources(root.childElement("sources"))

        // Parse <dependencies> (optional) — both for plugin deps and info field
        val depsElem = root.optionalChildElement("dependencies")
        val deps = depsElem?.let { parseDependencies(it) } ?: emptyList()
        val depIds = deps.map { it.id }

        // Rebuild info with dependencies (so PluginInfo.dependencies is populated)
        val infoWithDeps = info.copy(dependencies = depIds)
        infoWithDeps.validate()

        // Parse <libraries> (optional)
        val libs = root.optionalChildElement("libraries")?.let { parseLibraries(it) } ?: emptyList()

        // Parse <versions>
        val versions = parseVersions(root.childElement("versions"))

        // Parse <resources> (optional)
        val resources = root.optionalChildElement("resources")?.let { parseResources(it) } ?: emptyList()

        // Cross-field validation
        validateCrossField(infoWithDeps, sources, versions, deps)

        return PluginPackage(formatVersion, infoWithDeps, sources, deps, libs, versions, resources)
    }

    private fun parseInfo(elem: Element): PluginInfo {
        val id = elem.childText("id") ?: ""
        val name = elem.childText("name") ?: ""
        val version = elem.childText("version") ?: ""
        val author = elem.childText("author") ?: ""
        val description = elem.childText("description") ?: ""
        val apiVersion = elem.childText("api-version") ?: ""
        val mainClass = elem.childText("main-class") ?: ""
        val website = elem.optionalChildText("website") ?: ""
        val license = elem.optionalChildText("license") ?: ""

        // dependencies will be set later from the top-level <dependencies> element
        return PluginInfo(
            id = id,
            name = name,
            version = version,
            author = author,
            description = description,
            apiVersion = apiVersion,
            mainClass = mainClass,
            dependencies = emptyList(),
            website = website,
            license = license
        )
    }

    private fun parseSources(elem: Element): List<SourceFile> {
        val sources = mutableListOf<SourceFile>()

        // Kotlin sources
        val ktElem = elem.optionalChildElement("kotlin")
        if (ktElem != null) {
            val files = ktElem.getElementsByTagName("file")
            for (i in 0 until files.length) {
                val f = files.item(i) as Element
                val path = f.getAttribute("path")
                val isMain = f.getAttribute("main") == "true"
                sources.add(SourceFile(path, isMain, SourceFile.Language.KOTLIN))
            }
        }

        // Java sources
        val javaElem = elem.optionalChildElement("java")
        if (javaElem != null) {
            val files = javaElem.getElementsByTagName("file")
            for (i in 0 until files.length) {
                val f = files.item(i) as Element
                val path = f.getAttribute("path")
                sources.add(SourceFile(path, false, SourceFile.Language.JAVA))
            }
        }

        return sources
    }

    private fun parseDependencies(elem: Element): List<PackageDependency> {
        val deps = mutableListOf<PackageDependency>()
        val depElems = elem.getElementsByTagName("dependency")
        for (i in 0 until depElems.length) {
            val e = depElems.item(i) as Element
            val id = e.getAttribute("id")
            val versionSpec = e.getAttribute("version").ifEmpty { "*" }
            deps.add(PackageDependency(id, versionSpec))
        }
        return deps
    }

    private fun parseLibraries(elem: Element): List<LibraryRef> {
        val libs = mutableListOf<LibraryRef>()
        val libElems = elem.getElementsByTagName("library")
        for (i in 0 until libElems.length) {
            val e = libElems.item(i) as Element
            libs.add(LibraryRef(e.getAttribute("path")))
        }
        return libs
    }

    private fun parseVersions(elem: Element): List<VersionEntry> {
        val versions = mutableListOf<VersionEntry>()
        val verElems = elem.getElementsByTagName("version")
        for (i in 0 until verElems.length) {
            val e = verElems.item(i) as Element
            val number = e.getAttribute("number")
            val date = e.getAttribute("date")
            val author = e.getAttribute("author")
            val changelog = e.textContent.trim()
            versions.add(VersionEntry(number, date, author, changelog))
        }
        return versions
    }

    private fun parseResources(elem: Element): List<ResourceRef> {
        val res = mutableListOf<ResourceRef>()
        val resElems = elem.getElementsByTagName("resource")
        for (i in 0 until resElems.length) {
            val e = resElems.item(i) as Element
            res.add(ResourceRef(e.getAttribute("path")))
        }
        return res
    }

    // ==================== Cross-Field Validation ====================

    private fun validateCrossField(
        info: PluginInfo,
        sources: List<SourceFile>,
        versions: List<VersionEntry>,
        deps: List<PackageDependency>
    ) {
        // Must have at least one Kotlin source
        val ktSources = sources.filter { it.language == SourceFile.Language.KOTLIN }
        if (ktSources.isEmpty()) {
            throw IllegalArgumentException(
                "<sources> must contain at least one <kotlin><file> entry. " +
                "A plugin package must have at least one Kotlin source file.")
        }

        // Exactly one main Kotlin source
        val mainSources = ktSources.filter { it.isMain }
        if (mainSources.isEmpty()) {
            throw IllegalArgumentException(
                "Exactly one Kotlin source file must have main=\"true\" attribute. " +
                "None found.")
        }
        if (mainSources.size > 1) {
            throw IllegalArgumentException(
                "Exactly one Kotlin source file must have main=\"true\" attribute. " +
                "Found ${mainSources.size} with main=\"true\".")
        }

        // Validate source file paths and check for duplicates
        val seenPaths = mutableSetOf<String>()
        for (src in sources) {
            if (!seenPaths.add(src.path)) {
                throw IllegalArgumentException(
                    "Duplicate source path: '${src.path}'. " +
                    "All source paths must be unique.")
            }
            when (src.language) {
                SourceFile.Language.KOTLIN -> {
                    require(src.path.startsWith("src/kt/")) {
                        "Kotlin source path must start with 'src/kt/', got: '${src.path}'"
                    }
                    require(src.path.endsWith(".kt")) {
                        "Kotlin source file must end with '.kt', got: '${src.path}'"
                    }
                }
                SourceFile.Language.JAVA -> {
                    require(src.path.startsWith("src/java/")) {
                        "Java source path must start with 'src/java/', got: '${src.path}'"
                    }
                    require(src.path.endsWith(".java")) {
                        "Java source file must end with '.java', got: '${src.path}'"
                    }
                }
            }
        }

        // Versions: at least one, and one must match info.version
        if (versions.isEmpty()) {
            throw IllegalArgumentException(
                "<versions> must contain at least one <version> entry.")
        }
        val hasMatchingVersion = versions.any { it.number == info.version }
        if (!hasMatchingVersion) {
            throw IllegalArgumentException(
                "<versions> must contain an entry matching the info version '${info.version}'. " +
                "Found versions: ${versions.joinToString { it.number }}")
        }

        // Validate version entry formats
        for (v in versions) {
            require(v.number.matches(PluginInfo.VERSION_REGEX)) {
                "Version entry number '${v.number}' is not valid semver"
            }
            require(v.date.matches(Regex("^\\d{4}-\\d{2}-\\d{2}\$"))) {
                "Version entry date must be YYYY-MM-DD format, got: '${v.date}'"
            }
            require(v.author.isNotBlank()) {
                "Version entry ${v.number} must have a non-blank author"
            }
        }

        // Validate dependency IDs
        val seenDepIds = mutableSetOf<String>()
        for (dep in deps) {
            require(PluginInfo.isValidId(dep.id)) {
                "Invalid dependency id '${dep.id}' in <dependencies> (must match plugin ID rules)"
            }
            require(seenDepIds.add(dep.id)) {
                "Duplicate dependency id '${dep.id}' in <dependencies>"
            }
            require(dep.versionSpec.isNotBlank()) {
                "Dependency '${dep.id}' must have a non-blank version spec"
            }
        }
    }

    private fun validatePathsExist(zip: ZipFile, pkg: PluginPackage) {
        // Check all source paths
        for (src in pkg.sources) {
            if (zip.getEntry(src.path) == null) {
                throw IllegalArgumentException(
                    "Source file '${src.path}' declared in plugin.xml but not found in package.")
            }
        }
        // Check all library paths
        for (lib in pkg.libraries) {
            require(lib.path.startsWith("lib/") && lib.path.endsWith(".jar")) {
                "Library path must start with 'lib/' and end with '.jar', got: '${lib.path}'"
            }
            if (zip.getEntry(lib.path) == null) {
                throw IllegalArgumentException(
                    "Library '${lib.path}' declared in plugin.xml but not found in package.")
            }
        }
        // Check all resource paths
        for (res in pkg.resources) {
            require(res.path.startsWith("resources/")) {
                "Resource path must start with 'resources/', got: '${res.path}'"
            }
            if (zip.getEntry(res.path) == null) {
                throw IllegalArgumentException(
                    "Resource '${res.path}' declared in plugin.xml but not found in package.")
            }
        }
        // Check properties file
        if (zip.getEntry(PROPERTIES_PATH) == null) {
            throw IllegalArgumentException(
                "Missing '$PROPERTIES_PATH' in package. " +
                "Both plugin.xml and the properties descriptor are required.")
        }
    }

    // ==================== DOM Helpers ====================

    private fun Element.childElement(tag: String): Element {
        val list = getElementsByTagName(tag)
        if (list.length == 0) {
            throw IllegalArgumentException("Missing required element <$tag> in plugin.xml")
        }
        return list.item(0) as Element
    }

    private fun Element.optionalChildElement(tag: String): Element? {
        val list = getElementsByTagName(tag)
        return if (list.length == 0) null else list.item(0) as Element
    }

    private fun Element.childText(tag: String): String? =
        optionalChildElement(tag)?.textContent?.trim()?.ifEmpty { null }

    private fun Element.optionalChildText(tag: String): String? =
        optionalChildElement(tag)?.textContent?.trim()?.ifEmpty { null }
}
