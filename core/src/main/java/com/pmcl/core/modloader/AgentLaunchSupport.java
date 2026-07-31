package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.launch.LaunchProfile;
import com.pmcl.core.install.VersionJson;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PMCL 扩展：从版本 JSON 的 {@code pmclAgents} 字段与 {@code agents/} 目录注入 -javaagent。
 * <p>
 * {@code pmclAgents} 条目格式：
 * <pre>
 * { "name": "com.unascribed:nilloader:1.3.6", "url": "https://repo.sleeping.town/" }
 * </pre>
 * 或直接：
 * <pre>
 * { "path": "relative/or/absolute.jar" }
 * </pre>
 */
public final class AgentLaunchSupport {

    private AgentLaunchSupport() {}

    public static void inject(LaunchProfile profile, VersionJson vj,
                              Path versionsDir, String versionId, Path librariesDir,
                              DownloadManager downloads) {
        if (profile == null || vj == null) return;
        JsonObject raw = vj.getRawJson();
        if (raw != null && raw.has("pmclAgents") && raw.get("pmclAgents").isJsonArray()) {
            for (JsonElement e : raw.getAsJsonArray("pmclAgents")) {
                if (!e.isJsonObject()) continue;
                try {
                    Path jar = resolveAgentJar(e.getAsJsonObject(), librariesDir, versionsDir, versionId, downloads);
                    if (jar != null && Files.isRegularFile(jar)) {
                        String opt = e.getAsJsonObject().has("options")
                                && !e.getAsJsonObject().get("options").isJsonNull()
                                ? e.getAsJsonObject().get("options").getAsString() : null;
                        profile.addJavaAgent(jar.toAbsolutePath().toString(),
                                (opt == null || opt.isBlank()) ? null : opt);
                    }
                } catch (Exception ex) {
                    System.err.println("[PMCL] pmclAgents 注入失败: " + ex.getMessage());
                }
            }
        }
        // versions/{id}/agents/*.jar
        try {
            Path agentsDir = versionsDir.resolve(versionId).resolve("agents");
            if (Files.isDirectory(agentsDir)) {
                List<Path> jars = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(agentsDir, "*.jar")) {
                    for (Path p : ds) jars.add(p);
                }
                jars.sort(Path::compareTo);
                for (Path jar : jars) {
                    profile.addJavaAgent(jar.toAbsolutePath().toString(), null);
                }
            }
        } catch (Exception ex) {
            System.err.println("[PMCL] agents/ 目录注入失败: " + ex.getMessage());
        }
    }

    private static Path resolveAgentJar(JsonObject entry, Path librariesDir, Path versionsDir,
                                        String versionId, DownloadManager downloads) throws IOException {
        if (entry.has("path") && !entry.get("path").isJsonNull()) {
            String p = entry.get("path").getAsString();
            Path path = Path.of(p);
            if (!path.isAbsolute()) {
                path = versionsDir.resolve(versionId).resolve(p).normalize();
            }
            return path;
        }
        if (!entry.has("name") || entry.get("name").isJsonNull()) return null;
        String name = entry.get("name").getAsString(); // group:artifact:version
        String[] parts = name.split(":");
        if (parts.length < 3) return null;
        String rel = mavenPath(parts[0], parts[1], parts[2]);
        Path dest = librariesDir.resolve(rel);
        if (Files.isRegularFile(dest) && Files.size(dest) > 64) return dest;
        String base = entry.has("url") && !entry.get("url").isJsonNull()
                ? entry.get("url").getAsString() : "https://repo.sleeping.town/";
        if (!base.endsWith("/")) base += "/";
        if (downloads == null) {
            throw new IOException("缺少 DownloadManager，无法下载 agent: " + name);
        }
        Files.createDirectories(dest.getParent());
        downloads.downloadTo(base + rel, dest);
        return dest;
    }

    static String mavenPath(String group, String artifact, String version) {
        return group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".jar";
    }

    static JsonObject agentEntry(String mavenName, String repoUrl) {
        JsonObject o = new JsonObject();
        o.addProperty("name", mavenName);
        o.addProperty("url", repoUrl);
        return o;
    }

    static JsonArray singleAgentArray(String mavenName, String repoUrl) {
        JsonArray arr = new JsonArray();
        arr.add(agentEntry(mavenName, repoUrl));
        return arr;
    }
}
