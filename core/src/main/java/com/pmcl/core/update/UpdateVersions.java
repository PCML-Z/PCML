package com.pmcl.core.update;

/**
 * Semver-ish version comparison for update channels (anti-downgrade).
 * <p>
 * Dot-separated segments compare by numeric core first, then by any trailing
 * letter/build suffix so {@code 1.3.0c} is newer than {@code 1.3.0}, while
 * {@code 1.3.1} remains newer than {@code 1.3.0c}.
 */
final class UpdateVersions {

    private UpdateVersions() {}

    /**
     * @return true iff {@code remote} is strictly newer than {@code current}
     */
    static boolean isNewer(String remote, String current) {
        if (remote == null || current == null) return false;
        if (remote.equals(current)) return false;
        String[] r = remote.split("\\.");
        String[] c = current.split("\\.");
        int len = Math.max(r.length, c.length);
        for (int i = 0; i < len; i++) {
            String rs = i < r.length ? r[i] : "0";
            String cs = i < c.length ? c[i] : "0";
            int ri = numericCore(rs);
            int ci = numericCore(cs);
            if (ri != ci) return ri > ci;
            int suffixCmp = letterSuffix(rs).compareTo(letterSuffix(cs));
            if (suffixCmp != 0) return suffixCmp > 0;
        }
        return false;
    }

    /** Leading digits of a segment; non-numeric-only segments become 0. */
    private static int numericCore(String segment) {
        try {
            String num = segment.replaceAll("[^0-9].*$", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Trailing non-digit suffix after the numeric core (e.g. {@code "0c"} → {@code "c"}). */
    private static String letterSuffix(String segment) {
        String num = segment.replaceAll("[^0-9].*$", "");
        if (num.isEmpty()) {
            return segment == null ? "" : segment;
        }
        return segment.substring(num.length());
    }
}
