package com.pmcl.core.modloader;

/**
 * Forge / NeoForge Maven 坐标解析（支持 classifier 与 {@code @ext}）。
 * <p>
 * 例：
 * <ul>
 *   <li>{@code g:a:v} → {@code g/a/v/a-v.jar}</li>
 *   <li>{@code g:a:v:c} → {@code g/a/v/a-v-c.jar}</li>
 *   <li>{@code g:a:v@zip} → {@code g/a/v/a-v.zip}</li>
 *   <li>{@code g:a:v:c@txt} → {@code g/a/v/a-v-c.txt}</li>
 * </ul>
 */
final class ForgeMavenCoords {

    private ForgeMavenCoords() {}

    /** 去掉外层 {@code [...]}（若有）。 */
    static String stripBrackets(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.startsWith("[") && t.endsWith("]") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 去掉 Forge data 里常见的单引号包裹。 */
    static String stripQuotes(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') {
            return t.substring(1, t.length() - 1);
        }
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * 坐标 → libraries 相对路径。
     * 旧 Forge universal 特例仍由调用方处理。
     */
    static String toPath(String coords) {
        String c = stripBrackets(coords);
        if (c.isEmpty()) return c;
        String ext = "jar";
        int at = c.lastIndexOf('@');
        if (at > 0) {
            ext = c.substring(at + 1);
            c = c.substring(0, at);
        }
        String[] parts = c.split(":");
        if (parts.length < 3) return c;
        String groupPath = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? parts[3] : null;
        StringBuilder sb = new StringBuilder()
                .append(groupPath).append('/')
                .append(artifact).append('/')
                .append(version).append('/')
                .append(artifact).append('-').append(version);
        if (classifier != null && !classifier.isEmpty()) {
            sb.append('-').append(classifier);
        }
        sb.append('.').append(ext);
        return sb.toString();
    }
}
