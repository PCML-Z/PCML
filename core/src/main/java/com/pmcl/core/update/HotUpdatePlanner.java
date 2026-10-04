package com.pmcl.core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 对照本地 app 目录，算出要下载的文件和要从旧清单里删掉的文件。 */
public final class HotUpdatePlanner {
    private HotUpdatePlanner() {}

    public static final class Plan {
        private final List<HotUpdateManifest.FileEntry> downloads;
        private final List<String> deletes;

        Plan(List<HotUpdateManifest.FileEntry> downloads, List<String> deletes) {
            this.downloads = List.copyOf(downloads);
            this.deletes = List.copyOf(deletes);
        }

        public List<HotUpdateManifest.FileEntry> downloads() { return downloads; }
        public List<String> deletes() { return deletes; }

        public long downloadBytes() {
            long n = 0;
            for (HotUpdateManifest.FileEntry file : downloads) n += Math.max(0, file.size());
            return n;
        }

        public boolean isEmpty() {
            return downloads.isEmpty() && deletes.isEmpty();
        }
    }

    public static Plan plan(Path appDir, HotUpdateManifest next, HotUpdateManifest previous,
                            HotUpdateManifest.Host host) throws IOException {
        if (appDir == null || !Files.isDirectory(appDir)) {
            throw new IOException("找不到安装目录里的 app");
        }
        List<HotUpdateManifest.FileEntry> downloads = new ArrayList<>();
        Set<String> nextPaths = new LinkedHashSet<>();
        for (HotUpdateManifest.FileEntry file : next.filesFor(host)) {
            nextPaths.add(file.path());
            Path dest = AppResourceCheck.resolve(appDir, file.path());
            if (!sameFile(dest, file)) downloads.add(file);
        }
        List<String> deletes = new ArrayList<>();
        if (previous != null) {
            for (HotUpdateManifest.FileEntry old : previous.filesFor(host)) {
                if (!nextPaths.contains(old.path())) deletes.add(old.path());
            }
        }
        return new Plan(downloads, deletes);
    }

    private static boolean sameFile(Path dest, HotUpdateManifest.FileEntry file) throws IOException {
        if (!Files.isRegularFile(dest) || Files.isSymbolicLink(dest)) return false;
        if (Files.size(dest) != file.size()) return false;
        if (!FileDigest.sha256(dest).equals(file.sha256())) return false;
        return FileDigest.contentLooksValid(dest);
    }
}
