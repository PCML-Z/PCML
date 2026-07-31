package com.pmcl.core.update;

/**
 * Semver-ish version comparison for update channels (anti-downgrade).
 */
final class UpdateVersions {

    private UpdateVersions() {}

    /**
     * @return true iff {@code remote} is strictly newer than {@code current}
     *         (dot-separated numeric segments; non-numeric suffixes ignored per segment)
     */
    static boolean isNewer(String remote, String current) {
        if (remote == null || current == null) return false;
        if (remote.equals(current)) return false;
        String[] r = remote.split("\\.");
        String[] c = current.split("\\.");
        int len = Math.max(r.length, c.length);
        for (int i = 0; i < len; i++) {
            int ri = i < r.length ? parseIntSafe(r[i]) : 0;
            int ci = i < c.length ? parseIntSafe(c[i]) : 0;
            if (ri > ci) return true;
            if (ri < ci) return false;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            String num = s.replaceAll("[^0-9].*$", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
