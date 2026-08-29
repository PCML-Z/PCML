package com.pmcl.core.plugin;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks closed plugin {@link ClassLoader}s via {@link PhantomReference} so the
 * host can detect <em>unreclaimed</em> loaders — the smoking gun for a plugin
 * leak (e.g. the plugin registered a global AWT/Beans listener or a JVM
 * shutdown hook that keeps a strong path to the loader alive).
 *
 * <p>A phantom reference enqueues <em>after</em> the referent has been finalized,
 * so an enqueued ref means the loader is reclaimable and no leak exists.
 * Refs that never enqueue (after an explicit GC) flag a leak.
 *
 * <p>Thread-safety: all state guarded by the detector's own monitor. Callers
 * that already hold the PluginManager lock pay no extra cost.
 */
final class ClassLoaderLeakDetector {

    private final ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();
    /** pluginId -> phantom ref (referent = the closed ClassLoader). */
    private final Map<String, PhantomReference<ClassLoader>> tracked = new LinkedHashMap<>();
    /** phantom ref -> pluginId, so we can identify which one enqueued. */
    private final Map<PhantomReference<ClassLoader>, String> refToId = new LinkedHashMap<>();

    /**
     * Begin tracking a freshly-closed plugin ClassLoader.
     * Call only after {@link ClassLoader#close()} so we observe the post-close
     * reachability graph.
     */
    synchronized void track(String pluginId, ClassLoader loader) {
        if (pluginId == null || loader == null) return;
        // Replace any stale entry for the same id (re-load after unload).
        PhantomReference<ClassLoader> old = tracked.remove(pluginId);
        if (old != null) refToId.remove(old);
        PhantomReference<ClassLoader> ref = new PhantomReference<>(loader, queue);
        tracked.put(pluginId, ref);
        refToId.put(ref, pluginId);
    }

    /**
     * Drain the reference queue (clearing now-reclaimable refs) and return the
     * pluginIds still tracked = whose loaders have <em>not</em> been reclaimed.
     * Call after {@code System.gc()} for a meaningful snapshot.
     */
    synchronized List<String> drainReclaimedAndReportUnreclaimed() {
        PhantomReference<?> r;
        while ((r = (PhantomReference<?>) queue.poll()) != null) {
            String id = refToId.remove(r);
            if (id != null) {
                tracked.remove(id);
                System.out.println("[PluginManager] ClassLoader for '"
                        + id + "' reclaimed by GC — no leak");
            }
            r.clear();
        }
        return new ArrayList<>(tracked.keySet());
    }

    /** Drop all tracking state (used on host shutdown). */
    synchronized void clear() {
        Iterator<PhantomReference<ClassLoader>> it = tracked.values().iterator();
        while (it.hasNext()) {
            it.next().clear();
            it.remove();
        }
        refToId.clear();
    }
}
