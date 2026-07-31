package com.pmcl.core.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Plugin trust / signing policy captured once at {@link PluginManager} construction.
 * <p>
 * Security-sensitive flags are <b>not</b> re-read from {@link System#getProperty} during
 * plugin load, so a plugin that can call {@code System.setProperty} cannot downgrade
 * global signer requirements at runtime (C3).
 */
final class PluginSecurityPolicy {

    final boolean allowUnsignedJars;
    final boolean allowUnsignedPackages;
    final boolean allowAnySigner;
    final boolean requireTrustedSigner;
    /** Fingerprints from {@code -Dpmcl.plugins.trustedFingerprints} at freeze time. */
    final Set<String> frozenPropFingerprints;
    /**
     * Optional outbound HTTP host allowlist from {@code -Dpmcl.plugins.httpAllowHosts}
     * (comma/space separated; exact host match, case-insensitive). Empty = SSRF-only.
     */
    final Set<String> httpAllowHosts;

    private PluginSecurityPolicy(
            boolean allowUnsignedJars,
            boolean allowUnsignedPackages,
            boolean allowAnySigner,
            boolean requireTrustedSigner,
            Set<String> frozenPropFingerprints,
            Set<String> httpAllowHosts) {
        this.allowUnsignedJars = allowUnsignedJars;
        this.allowUnsignedPackages = allowUnsignedPackages;
        this.allowAnySigner = allowAnySigner;
        this.requireTrustedSigner = requireTrustedSigner;
        this.frozenPropFingerprints = Collections.unmodifiableSet(frozenPropFingerprints);
        this.httpAllowHosts = Collections.unmodifiableSet(httpAllowHosts);
    }

    static PluginSecurityPolicy captureAtStartup() {
        boolean allowUnsigned = Boolean.parseBoolean(
                System.getProperty("pmcl.plugins.allowUnsigned", "false"));
        String requireProp = System.getProperty("pmcl.plugins.requireSigned");
        if (requireProp != null) {
            allowUnsigned = !Boolean.parseBoolean(requireProp);
        }
        boolean allowUnsignedPkg = Boolean.parseBoolean(
                System.getProperty("pmcl.plugins.allowUnsignedPackages", "false"));
        boolean allowAnySigner = Boolean.parseBoolean(
                System.getProperty("pmcl.plugins.allowAnySigner", "false"));
        boolean requireTrusted = Boolean.parseBoolean(
                System.getProperty("pmcl.plugins.requireTrustedSigner", "true"));

        Set<String> fps = new LinkedHashSet<>();
        String prop = System.getProperty("pmcl.plugins.trustedFingerprints", "");
        if (prop != null && !prop.isBlank()) {
            for (String part : prop.split("[,;\\s]+")) {
                String n = normalizeFingerprint(part);
                if (!n.isEmpty()) fps.add(n);
            }
        }
        Set<String> hosts = new LinkedHashSet<>();
        String hostProp = System.getProperty("pmcl.plugins.httpAllowHosts", "");
        if (hostProp != null && !hostProp.isBlank()) {
            for (String part : hostProp.split("[,;\\s]+")) {
                String h = part.trim().toLowerCase(Locale.ROOT);
                if (!h.isEmpty()) hosts.add(h);
            }
        }
        if (allowUnsignedPkg) {
            System.err.println("[PluginManager] NOTE: -Dpmcl.plugins.allowUnsignedPackages is ignored; "
                    + ".ppk packages always require jarsigner signatures (C2).");
        }
        return new PluginSecurityPolicy(
                allowUnsigned, allowUnsignedPkg, allowAnySigner, requireTrusted, fps, hosts);
    }

    /**
     * Trusted fingerprints = frozen JVM property set ∪ {@code trusted-signers.txt}.
     * File may be edited by the user; it is not driven by {@code System.setProperty}.
     */
    Set<String> loadTrustedFingerprints(Path pluginsDir) {
        Set<String> out = new LinkedHashSet<>(frozenPropFingerprints);
        Path file = pluginsDir.resolve("trusted-signers.txt");
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    String n = normalizeFingerprint(t);
                    if (!n.isEmpty()) out.add(n);
                }
            } catch (IOException e) {
                System.err.println("[PluginManager] Failed to read trusted-signers.txt: " + e.getMessage());
            }
        }
        return out;
    }

    static String normalizeFingerprint(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("sha256:")) s = s.substring("sha256:".length());
        s = s.replace(":", "").replace(" ", "");
        if (!s.matches("[0-9a-f]{64}")) return "";
        return s;
    }
}
