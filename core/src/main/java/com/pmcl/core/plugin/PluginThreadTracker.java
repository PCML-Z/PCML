package com.pmcl.core.plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-plugin {@link ThreadGroup} tracking so unload can interrupt plugin-owned threads.
 * <p>
 * Threads created with {@code new Thread(runnable)} inherit the <em>current</em> thread's
 * group. Host entry points therefore execute plugin code on a worker that already belongs
 * to the plugin group; {@link #newThread} / {@link #threadFactory} always bind explicitly.
 */
final class PluginThreadTracker {

    /** Parent of all plugin groups (under the system group). */
    static final ThreadGroup ROOT = new ThreadGroup("pmcl-plugins");

    private static final AtomicLong WORKER_SEQ = new AtomicLong();

    private final String pluginId;
    private final ThreadGroup group;
    private volatile ClassLoader contextClassLoader;
    private volatile boolean destroyed;

    PluginThreadTracker(String pluginId) {
        this.pluginId = pluginId;
        this.group = new ThreadGroup(ROOT, "pmcl-plugin-" + pluginId);
    }

    ThreadGroup group() {
        return group;
    }

    void setContextClassLoader(ClassLoader cl) {
        this.contextClassLoader = cl;
    }

    boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Create a daemon thread in this plugin's group with the plugin ClassLoader as CCL.
     */
    Thread newThread(String name, Runnable task) {
        if (destroyed) {
            throw new IllegalStateException("Plugin '" + pluginId + "' thread group is destroyed");
        }
        String threadName = (name == null || name.isBlank())
                ? "pmcl-plugin-" + pluginId + "-" + WORKER_SEQ.incrementAndGet()
                : name;
        Runnable wrapped = wrap(task);
        Thread t = new Thread(group, wrapped, threadName);
        t.setDaemon(true);
        ClassLoader cl = contextClassLoader;
        if (cl != null) {
            t.setContextClassLoader(cl);
        }
        return t;
    }

    java.util.concurrent.ThreadFactory threadFactory(String namePrefix) {
        String prefix = (namePrefix == null || namePrefix.isBlank())
                ? "pmcl-plugin-" + pluginId + "-"
                : namePrefix;
        return r -> newThread(prefix + WORKER_SEQ.incrementAndGet(), r);
    }

    /**
     * Run {@code task} on a thread belonging to this plugin's group and wait for completion.
     * If the caller is already in this group, runs inline (avoids nested workers).
     */
    void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    <T> T call(Callable<T> task) {
        if (task == null) throw new NullPointerException("task");
        if (destroyed) {
            throw new IllegalStateException("Plugin '" + pluginId + "' thread group is destroyed");
        }
        if (belongsToThisGroup(Thread.currentThread())) {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            ClassLoader cl = contextClassLoader;
            try {
                if (cl != null) Thread.currentThread().setContextClassLoader(cl);
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(old);
            }
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = newThread("pmcl-plugin-" + pluginId + "-gate-" + WORKER_SEQ.incrementAndGet(), () -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for plugin '" + pluginId + "' work", e);
        }
        Throwable err = error.get();
        if (err != null) {
            if (err instanceof RuntimeException) throw (RuntimeException) err;
            if (err instanceof Error) throw (Error) err;
            throw new RuntimeException(err);
        }
        return result.get();
    }

    /**
     * Interrupt all live threads in the group and wait briefly for them to exit.
     * Safe to call multiple times.
     */
    void shutdown(long waitMs) {
        destroyed = true;
        try {
            group.interrupt();
        } catch (Throwable t) {
            System.err.println("[Plugin:" + pluginId + "] ThreadGroup.interrupt failed: " + t.getMessage());
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMs);
        while (System.currentTimeMillis() < deadline) {
            int alive = activeAliveCount();
            if (alive <= 0) break;
            Thread[] threads = new Thread[alive + 8];
            int n = group.enumerate(threads, /* recurse */ false);
            boolean joinedAny = false;
            for (int i = 0; i < n; i++) {
                Thread t = threads[i];
                if (t == null || !t.isAlive() || t == Thread.currentThread()) continue;
                try {
                    t.join(50);
                    joinedAny = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!joinedAny) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int remaining = activeAliveCount();
        if (remaining > 0) {
            System.err.println("[Plugin:" + pluginId + "] WARNING: " + remaining
                    + " thread(s) still alive after ThreadGroup shutdown — interrupting again");
            try {
                group.interrupt();
            } catch (Throwable ignored) {}
            Thread[] threads = new Thread[remaining + 8];
            int n = group.enumerate(threads, false);
            for (int i = 0; i < n; i++) {
                Thread t = threads[i];
                if (t != null && t.isAlive()) {
                    System.err.println("[Plugin:" + pluginId + "]   alive: " + t.getName()
                            + " state=" + t.getState());
                }
            }
        } else {
            // Leave the empty group in place; ThreadGroup.destroy() is deprecated for removal.
        }
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            ClassLoader cl = contextClassLoader;
            try {
                if (cl != null) Thread.currentThread().setContextClassLoader(cl);
                task.run();
            } finally {
                Thread.currentThread().setContextClassLoader(old);
            }
        };
    }

    private boolean belongsToThisGroup(Thread thread) {
        ThreadGroup g = thread.getThreadGroup();
        while (g != null) {
            if (g == group) return true;
            g = g.getParent();
        }
        return false;
    }

    private int activeAliveCount() {
        int estimate = Math.max(group.activeCount(), 0);
        Thread[] threads = new Thread[estimate + 8];
        int n = group.enumerate(threads, false);
        int alive = 0;
        for (int i = 0; i < n; i++) {
            if (threads[i] != null && threads[i].isAlive()) alive++;
        }
        return alive;
    }
}
