package com.pmcl.core.nativeclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 独立于 Java 版的社区客户端。发行包来自它们自己的 GitHub Release，渲染固定走 Vulkan。
 */
public final class NativeClientCatalog {

    public enum AssetLayout {
        /** {@code assets/indexes} + {@code assets/objects}，再从客户端 jar 抽出 {@code assets/minecraft}。 */
        HASH_STORE,
        /** 按资源索引把哈希对象还原成 {@code runtime/assets} 下的逻辑路径。 */
        LOGICAL,
        /** 客户端自带资源，不借用本机的 Minecraft 安装。 */
        NONE
    }

    public enum BuildTool {
        CARGO, GO, CMAKE, MAKE, DOTNET, ZIG
    }

    public static final class Spec {
        public final String id;
        public final String nameKey;
        public final String summaryKey;
        public final String owner;
        public final String repo;
        public final String minecraftVersion;
        final AssetLayout layout;
        final String[] executableNames;
        final String[] launchArgs;
        /** 非空时启动写入该环境变量，用来选中 Vulkan。 */
        final String vulkanEnvKey;
        final String vulkanEnvValue;
        /** 非空时在工作目录的 options.txt 里写这个键。 */
        final String optionsKey;
        final BuildTool buildTool;
        /** 已知有发行包的 {@code 系统-架构}，逗号分隔。空表示只能本地编译。 */
        final String platforms;
        final boolean vulkan;

        Spec(String id, String nameKey, String summaryKey, String owner, String repo,
             String minecraftVersion, AssetLayout layout, String[] executableNames, String[] launchArgs,
             String vulkanEnvKey, String vulkanEnvValue, String optionsKey,
             BuildTool buildTool, String platforms, boolean vulkan) {
            this.id = id;
            this.nameKey = nameKey;
            this.summaryKey = summaryKey;
            this.owner = owner;
            this.repo = repo;
            this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
            this.layout = layout;
            this.executableNames = executableNames;
            this.launchArgs = launchArgs;
            this.vulkanEnvKey = vulkanEnvKey;
            this.vulkanEnvValue = vulkanEnvValue;
            this.optionsKey = optionsKey;
            this.buildTool = buildTool;
            this.platforms = platforms == null ? "" : platforms;
            this.vulkan = vulkan;
        }

        boolean publishesHere(String os, String arch) {
            String exact = os + "-" + arch;
            String any = os + "-any";
            for (String item : platforms.split(",")) {
                String token = item.trim();
                if (token.equals(exact) || token.equals(any)) return true;
            }
            return false;
        }

        public String releaseApi() {
            return "https://api.github.com/repos/" + owner + "/" + repo + "/releases?per_page=10";
        }
    }

    public static final class AssetChoice {
        public final String tag;
        public final String name;
        public final String url;
        public final long size;

        AssetChoice(String tag, String name, String url, long size) {
            this.tag = tag;
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    private static final List<Spec> SPECS = List.of(
            spec("rustcraft", "native.rustcraft.name", "native.rustcraft.summary",
                    "RustCraftMC", "RustCraft-Public", "1.8.9", AssetLayout.HASH_STORE,
                    new String[]{"rustcraft", "rustcraft.exe"}, new String[0],
                    "RUSTCRAFT_RENDER_BACKEND", "vulkan", null,
                    BuildTool.CARGO, "windows-x64,linux-x64", true),
            spec("mc112-rust", "native.mc112.name", "native.mc112.summary",
                    "zxy168zxy168-max", "Minecraft-1.12.2-Rust", "1.12.2", AssetLayout.LOGICAL,
                    new String[]{"mc112-client", "mc112-client.exe"},
                    new String[]{"run", "--assets", "runtime/assets"},
                    null, null, "rustRenderBackend",
                    BuildTool.CARGO, "", true),
            spec("pomme", "native.pomme.name", "native.pomme.summary",
                    "PommeMC", "Client", "26.2", AssetLayout.NONE,
                    new String[]{"pomme", "pomme-client", "client", "pomme.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "windows-x64,linux-x64,macos-aarch64", false),
            spec("classicube", "native.classicube.name", "native.classicube.summary",
                    "ClassiCube", "ClassiCube", "", AssetLayout.NONE,
                    new String[]{"ClassiCube", "classicube", "ClassiCube.exe"}, new String[0],
                    null, null, null,
                    BuildTool.MAKE, "windows-x64,linux-x64,macos-x64,macos-aarch64", false),
            spec("alex", "native.alex.name", "native.alex.summary",
                    "ConcreteMC", "Alex", "", AssetLayout.NONE,
                    new String[]{"Alex", "Alex.exe", "alex"}, new String[0],
                    null, null, null,
                    BuildTool.DOTNET, "windows-x64,linux-x64", false),
            spec("leafish", "native.leafish.name", "native.leafish.summary",
                    "Lea-fish", "Leafish", "", AssetLayout.NONE,
                    new String[]{"leafish", "Leafish", "leafish.exe", "LeafishInstaller.jar"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "windows-x64,linux-x64", false),
            spec("stevenarella", "native.stevenarella.name", "native.stevenarella.summary",
                    "iceiix", "stevenarella", "", AssetLayout.NONE,
                    new String[]{"stevenarella", "steven", "stevenarella.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("mce", "native.mce.name", "native.mce.summary",
                    "Minecraft-Community-Edition", "client", "", AssetLayout.NONE,
                    new String[]{"client", "minecraft", "Minecraft", "client.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CMAKE, "", false),
            spec("polymer", "native.polymer.name", "native.polymer.summary",
                    "atxi", "Polymer", "", AssetLayout.NONE,
                    new String[]{"polymer", "Polymer", "polymer.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CMAKE, "", true),
            spec("steven", "native.steven.name", "native.steven.summary",
                    "Thinkofname", "steven", "", AssetLayout.NONE,
                    new String[]{"steven", "steven.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("steven-go", "native.steven_go.name", "native.steven_go.summary",
                    "Thinkofname", "steven-go", "", AssetLayout.NONE,
                    new String[]{"steven", "steven.exe", "steven-go"}, new String[0],
                    null, null, null,
                    BuildTool.GO, "", false),
            spec("mcre", "native.mcre.name", "native.mcre.summary",
                    "mcre-engine", "mcre", "", AssetLayout.NONE,
                    new String[]{"mcre", "mcre.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("cinnabar", "native.cinnabar.name", "native.cinnabar.summary",
                    "bedrock-mc", "cinnabar", "", AssetLayout.NONE,
                    new String[]{"cinnabar", "cinnabar.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "linux-x64", false),
            spec("mcc", "native.mcc.name", "native.mcc.summary",
                    "MCCTeam", "Minecraft-Console-Client", "", AssetLayout.NONE,
                    new String[]{"MinecraftClient", "MinecraftClient.exe"}, new String[0],
                    null, null, null,
                    BuildTool.DOTNET,
                    "windows-x64,windows-aarch64,linux-x64,linux-aarch64,macos-x64,macos-aarch64",
                    false),
            spec("zuri", "native.zuri.name", "native.zuri.summary",
                    "zuri-mc", "zuri", "", AssetLayout.NONE,
                    new String[]{"zuri", "zuri.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("truecraft", "native.truecraft.name", "native.truecraft.summary",
                    "ddevault", "TrueCraft", "", AssetLayout.NONE,
                    new String[]{"TrueCraft", "TrueCraft.exe"}, new String[0],
                    null, null, null,
                    BuildTool.DOTNET, "", false),
            spec("crosscraft", "native.crosscraft.name", "native.crosscraft.summary",
                    "CrossCraft", "CrossCraft-Classic", "", AssetLayout.NONE,
                    new String[]{"CrossCraft", "CrossCraft.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CMAKE, "windows-x64,linux-x64", false),
            spec("brine", "native.brine.name", "native.brine.summary",
                    "BGR360", "brine", "", AssetLayout.NONE,
                    new String[]{"brine", "brine.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("bedrock", "native.bedrock.name", "native.bedrock.summary",
                    "ismaileke", "bedrock-client", "", AssetLayout.NONE,
                    new String[]{"bedrock-client", "bedrock-client.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "windows-x64", false),
            spec("litecraft", "native.litecraft.name", "native.litecraft.summary",
                    "KernelFreeze", "Litecraft", "", AssetLayout.NONE,
                    new String[]{"litecraft", "litecraft.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("minecrab", "native.minecrab.name", "native.minecrab.summary",
                    "cohaereo", "minecrab", "1.7.10", AssetLayout.NONE,
                    new String[]{"minecrab", "minecrab.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("mchprc", "native.mchprc.name", "native.mchprc.summary",
                    "MCHPR", "MCHPRC", "", AssetLayout.NONE,
                    new String[]{"mchprc", "mchprc.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("terracotta", "native.terracotta.name", "native.terracotta.summary",
                    "plushmonkey", "Terracotta", "1.13.2", AssetLayout.NONE,
                    new String[]{"terracotta", "terracotta.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CMAKE, "", false),
            spec("meteorite", "native.meteorite.name", "native.meteorite.summary",
                    "MineGame159", "meteorite", "", AssetLayout.NONE,
                    new String[]{"Meteorite", "Meteorite.exe", "Client", "Client.exe"}, new String[0],
                    null, null, null,
                    BuildTool.DOTNET, "", false),
            spec("crabcraft", "native.crabcraft.name", "native.crabcraft.summary",
                    "Solenopsisbot", "CrabCraft", "", AssetLayout.NONE,
                    new String[]{"crabcraft", "crabcraft.exe"}, new String[0],
                    null, null, null,
                    BuildTool.CARGO, "", false),
            spec("maincraft", "native.maincraft.name", "native.maincraft.summary",
                    "Guigui220D", "MainCraft", "b1.7.3", AssetLayout.NONE,
                    new String[]{"maincraft", "maincraft.exe"}, new String[0],
                    null, null, null,
                    BuildTool.ZIG, "", false),
            spec("zig-client", "native.zig_client.name", "native.zig_client.summary",
                    "InspectorBoat", "zig-client", "1.8.9", AssetLayout.NONE,
                    new String[]{"zig-client", "zig-client.exe"}, new String[0],
                    null, null, null,
                    BuildTool.ZIG, "", false),
            spec("corncraft", "native.corncraft.name", "native.corncraft.summary",
                    "DevBobcorn", "CornCraft", "", AssetLayout.NONE,
                    new String[]{"CornCraft.exe"}, new String[0],
                    null, null, null,
                    BuildTool.DOTNET, "windows-x64", false)
    );

    private static Spec spec(String id, String nameKey, String summaryKey, String owner, String repo,
                             String minecraftVersion, AssetLayout layout, String[] executableNames,
                             String[] launchArgs, String vulkanEnvKey, String vulkanEnvValue, String optionsKey,
                             BuildTool buildTool, String platforms, boolean vulkan) {
        return new Spec(id, nameKey, summaryKey, owner, repo, minecraftVersion, layout, executableNames,
                launchArgs, vulkanEnvKey, vulkanEnvValue, optionsKey, buildTool, platforms, vulkan);
    }

    private NativeClientCatalog() {}

    public static List<Spec> all() {
        return SPECS;
    }

    public static Spec find(String id) {
        if (id == null) return null;
        for (Spec spec : SPECS) {
            if (spec.id.equals(id)) return spec;
        }
        return null;
    }

    public static boolean supportedHere() {
        String os = osToken();
        String arch = archToken();
        return ("windows".equals(os) || "linux".equals(os)) && "x64".equals(arch);
    }

    static String osToken() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        return "linux";
    }

    static String archToken() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        return "x64";
    }

    /**
     * 在 GitHub releases JSON 里选当前系统的发行包。列表按发布时间从新到旧。
     * 没有对应平台的包时返回 null。
     */
    /**
     * 只接受这个客户端声明过的平台。没有声明时即使仓库里有 jar，也交给用户选的编译器。
     */
    static AssetChoice selectPublished(Spec spec, String releasesJson, String os, String arch) {
        if (spec == null || !spec.publishesHere(os, arch)) return null;
        return selectAsset(releasesJson, os, arch);
    }

    static AssetChoice selectAsset(String releasesJson, String os, String arch) {
        JsonElement parsed = com.google.gson.JsonParser.parseString(releasesJson);
        if (!parsed.isJsonArray()) return null;
        JsonArray releases = parsed.getAsJsonArray();
        for (JsonElement releaseElement : releases) {
            if (!releaseElement.isJsonObject()) continue;
            JsonObject release = releaseElement.getAsJsonObject();
            if (release.has("draft") && release.get("draft").getAsBoolean()) continue;
            JsonArray assets = release.has("assets") && release.get("assets").isJsonArray()
                    ? release.getAsJsonArray("assets") : new JsonArray();
            AssetChoice best = null;
            int bestScore = -1;
            for (JsonElement assetElement : assets) {
                if (!assetElement.isJsonObject()) continue;
                JsonObject asset = assetElement.getAsJsonObject();
                String name = text(asset, "name");
                String url = text(asset, "browser_download_url");
                if (name.isEmpty() || !url.startsWith("https://")) continue;
                long size = asset.has("size") ? asset.get("size").getAsLong() : 0L;
                if (size > 512L * 1024 * 1024) continue;
                int score = scoreAsset(name, os, arch);
                if (score > bestScore) {
                    bestScore = score;
                    best = new AssetChoice(text(release, "tag_name"), name, url, size);
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    static int scoreAsset(String name, String os, String arch) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.endsWith(".sha256") || n.endsWith(".sha512") || n.contains("source")) return -1;
        boolean osMatch = matchesOs(n, os);
        boolean jar = n.endsWith(".jar");
        boolean archive = n.endsWith(".zip") || n.endsWith(".appimage") || n.endsWith(".exe")
                || n.endsWith(".dmg") || jar;
        // 没有扩展名、文件名里已经写了系统的发布文件，例如 MinecraftClient-...-osx-arm64。
        boolean bareBinary = n.indexOf('.') < 0 && osMatch;
        if (!archive && !bareBinary) return -1;
        boolean neutral = !hasOsToken(n) && (n.endsWith(".zip") || jar);
        if (!osMatch && !neutral) return -1;
        boolean namedX64 = n.contains("x86_64") || n.contains("x64") || n.contains("amd64") || n.contains("x86-64");
        boolean namedArm = n.contains("aarch64") || n.contains("arm64");
        if ("aarch64".equals(arch)) {
            if (namedX64 && !namedArm) return -1;
        } else if (namedArm && !namedX64) {
            return -1;
        }
        int score = osMatch ? 10 : 6;
        if ("x64".equals(arch) && namedX64) score += 5;
        if ("aarch64".equals(arch) && namedArm) score += 5;
        if (n.endsWith(".zip")) score += 4;
        if (n.contains(".1.zip")) score -= 3;
        if (n.endsWith(".appimage")) score += 1;
        if (jar) score -= 2;
        return score;
    }

    private static boolean hasOsToken(String name) {
        return name.contains("windows") || name.contains("win64") || name.endsWith(".exe")
                || name.contains("linux") || name.contains("appimage")
                || name.contains("macos") || name.contains("darwin") || name.contains("osx");
    }

    private static boolean matchesOs(String name, String os) {
        boolean windows = name.contains("windows") || name.contains("win64") || name.endsWith(".exe");
        boolean linux = name.contains("linux") || name.contains("appimage");
        boolean macos = name.contains("macos") || name.contains("darwin") || name.contains("osx");
        if ("windows".equals(os)) return windows && !linux && !macos;
        if ("linux".equals(os)) return linux && !windows && !macos;
        if ("macos".equals(os)) return macos && !windows && !linux;
        return false;
    }

    /** 保留 options.txt 里的其他行，只改渲染后端。 */
    static String withBackend(String existing, String key, String value) {
        String line = key + ":" + value;
        List<String> out = new ArrayList<>();
        boolean replaced = false;
        String source = existing == null ? "" : existing;
        for (String raw : source.split("\n", -1)) {
            String trimmed = raw.trim();
            if (trimmed.equals(key) || trimmed.startsWith(key + ":")) {
                if (!replaced) out.add(line);
                replaced = true;
            } else if (!trimmed.isEmpty()) {
                out.add(raw.stripTrailing());
            }
        }
        if (!replaced) out.add(line);
        return String.join("\n", out) + "\n";
    }

    private static String text(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return "";
        return object.get(key).getAsString();
    }
}
