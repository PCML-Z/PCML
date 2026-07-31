package com.pmcl.core.plugin;

/**
 * Host-side bridge used by {@link PluginIsolatingClassLoader}.
 * <p>
 * Parent is the platform loader (not the application loader), so
 * {@code bridge.getParent().loadClass("com.pmcl.core...")} fails.
 * {@link #loadClass} only allows plugin API / approved third-party packages
 * from the real application ClassLoader.
 */
final class FilteringHostClassLoader extends ClassLoader {

    private static final String[] ALLOWED_PMCL = { "com.pmcl.plugin." };

    private static final String[] ALLOWED_THIRD_PARTY = {
            "androidx.compose.",
            "kotlin.",
            "kotlinx.",
            "org.jetbrains.annotations.",
            "org.intellij.lang.annotations.",
            "com.google.gson.",
            "org.slf4j.",
            "javafx.",
            // JavaFX natives / toolkit impl (must match PluginIsolatingClassLoader)
            "com.sun.javafx.",
            "com.sun.glass.",
            "com.sun.prism.",
            "com.sun.scenario.",
            "com.sun.openpisces.",
    };

    private final ClassLoader application;

    FilteringHostClassLoader(ClassLoader application) {
        super(ClassLoader.getPlatformClassLoader());
        this.application = application;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("com.pmcl.core.") || isBlockedPmcl(name)) {
            throw new SecurityException("Host bridge refuses PMCL internal class: " + name);
        }
        if (!isAllowed(name)) {
            throw new ClassNotFoundException("Host bridge does not export: " + name);
        }
        Class<?> c = application.loadClass(name);
        if (resolve) {
            resolveClass(c);
        }
        return c;
    }

    private static boolean isBlockedPmcl(String name) {
        if (!name.startsWith("com.pmcl.")) return false;
        for (String p : ALLOWED_PMCL) {
            if (name.startsWith(p)) return false;
        }
        return true;
    }

    private static boolean isAllowed(String name) {
        for (String p : ALLOWED_PMCL) {
            if (name.startsWith(p)) return true;
        }
        for (String p : ALLOWED_THIRD_PARTY) {
            if (name.startsWith(p)) return true;
        }
        return name.equals("com.sun.util.PropertyHelper");
    }
}
