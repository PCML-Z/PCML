package com.pmcl.music.source;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地音频源：支持绝对路径 / file:// URI。
 * 不经 {@link com.pmcl.core.util.SsrfChecker}（仅本地文件）。
 */
public class LocalAudioSource implements AudioSource {

    private static final String TYPE = "local";
    private static final Set<String> EXT = Set.of(
            "mp3", "flac", "m4a", "aac", "ogg", "wav", "opus", "wma", "aiff", "ape"
    );

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean matches(String url) {
        if (url == null || url.isBlank()) return false;
        String t = url.trim();
        if (t.regionMatches(true, 0, "file:", 0, 5)) return true;
        // 绝对路径（Unix / Windows）
        if (t.startsWith("/") || (t.length() >= 3 && Character.isLetter(t.charAt(0)) && t.charAt(1) == ':' )) {
            return hasAudioExt(t) || new File(t).isFile();
        }
        return false;
    }

    @Override
    public AudioStreamInfo resolve(String url) throws IOException {
        Path path = toPath(url);
        if (!Files.isRegularFile(path)) {
            throw new IOException("本地文件不存在: " + path);
        }
        String name = path.getFileName().toString();
        String title = stripExt(name);
        return new AudioStreamInfo(
                title,
                "",
                0L,
                path.toAbsolutePath().toString(),
                "",
                TYPE,
                path.toAbsolutePath().toString(),
                Map.of(),
                path.toAbsolutePath().toString()
        );
    }

    public static boolean isSupportedAudioFile(File f) {
        return f != null && f.isFile() && hasAudioExt(f.getName());
    }

    public static boolean hasAudioExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static Path toPath(String url) {
        String t = url.trim();
        if (t.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                return Path.of(java.net.URI.create(t));
            } catch (Exception e) {
                String stripped = t.substring(5);
                while (stripped.startsWith("/")) {
                    // file:///path → /path ; file://localhost/path
                    if (stripped.startsWith("///")) {
                        stripped = stripped.substring(2);
                        break;
                    }
                    stripped = stripped.substring(1);
                }
                return Path.of(stripped);
            }
        }
        return Path.of(t);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
