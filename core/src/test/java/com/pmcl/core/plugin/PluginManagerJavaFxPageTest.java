package com.pmcl.core.plugin;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.LauncherCore;
import com.pmcl.plugin.ComposableContent;
import com.pmcl.plugin.JavaFxContent;
import com.pmcl.plugin.JavaFxPageFactories;
import com.pmcl.plugin.JavaFxPageFactory;
import com.pmcl.plugin.PluginInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PluginContextImpl.registerJavaFxPage} 行为测试。
 *
 * 覆盖三条路径：
 * 1. 工厂可用（模拟 UI 宿主已注册 [JavaFxPageFactory]）且插件已加载
 *    → 工厂在插件线程上下文中被调用，返回的 ComposableContent 注册为普通页面
 * 2. 工厂不可用（headless 宿主）→ 降级注册 unavailableContent 占位页，不抛异常
 * 3. 参数校验与复用的 registerPage 管道（非法 id/title、重复 id、null content）
 *
 * core 测试 classpath 无 JavaFX：[JavaFxContent] 用动态代理构造（不引用
 * javafx.scene.Parent 即可编译/运行——这正是宿主在无 UI 环境持有内容实例的形态）。
 */
class PluginManagerJavaFxPageTest {

    private static final String PLUGIN_ID = "test-fx-plugin";

    @TempDir
    Path tempDir;

    private LauncherCore core;
    private PluginManager manager;
    private PluginManager.PluginContextImpl context;

    @BeforeEach
    void setUp() throws Exception {
        core = new LauncherCore(new LauncherConfig(tempDir));
        manager = new PluginManager(core);
        context = new PluginManager.PluginContextImpl(manager, PLUGIN_ID);
        JavaFxPageFactories.clear();
    }

    @AfterEach
    void tearDown() {
        // 恢复全局注册表，避免污染同 JVM 的其他测试
        JavaFxPageFactories.clear();
    }

    // ========================================================================
    // 路径 1：工厂可用 + 插件已加载 → 正常注册
    // ========================================================================

    @Test
    @DisplayName("工厂可用且插件已加载：工厂收到原 content，返回值注册为页面")
    void registersFactoryProducedPageWhenPluginLoaded() throws Exception {
        loadFakePluginEntry();

        JavaFxContent pluginContent = proxyJavaFxContent();
        // ComposableContent.invoke 经 compose-compiler 改写为 (Composer,int) 签名，
        // Java 无法用无参 lambda 实现；用 unavailableContent() 提供现成实例
        ComposableContent produced = JavaFxPageFactories.unavailableContent();
        List<JavaFxContent> received = new ArrayList<>();
        JavaFxPageFactories.register(content -> {
            received.add(content);
            return produced;
        });

        context.registerJavaFxPage("fx-view", "FX View", pluginContent);

        assertEquals(1, received.size(), "工厂应恰好被调用一次");
        assertSame(pluginContent, received.get(0), "工厂应原样收到插件提供的 JavaFxContent");

        PluginManager.RegisteredPage page = findPage("fx-view");
        assertNotNull(page, "页面应已注册");
        assertEquals(PLUGIN_ID, page.pluginId);
        assertEquals("FX View", page.title);
        assertSame(produced, page.content, "注册的应是工厂返回的 ComposableContent");
    }

    @Test
    @DisplayName("工厂可用但插件未加载：拒绝执行且不注册页面（工厂代码必须在插件线程组运行）")
    void rejectsFactoryInvocationWhenPluginNotLoaded() {
        // 不加载插件 entry：callInPlugin 必须拒绝而非内联执行
        AtomicInteger factoryCalls = new AtomicInteger();
        JavaFxPageFactories.register(content -> {
            factoryCalls.incrementAndGet();
            return JavaFxPageFactories.unavailableContent();
        });

        assertThrows(IllegalStateException.class,
                () -> context.registerJavaFxPage("fx-view", "FX View", proxyJavaFxContent()),
                "插件未加载时应抛 IllegalStateException");

        assertEquals(0, factoryCalls.get(), "工厂不应被调用");
        assertTrue(findPagesByPlugin().isEmpty(), "失败时不应留下注册页面");
    }

    // ========================================================================
    // 路径 2：headless 宿主（无工厂）→ 降级占位页
    // ========================================================================

    @Test
    @DisplayName("无 UI 宿主：降级注册占位页而非抛异常（headless 兼容契约）")
    void fallsBackToPlaceholderWithoutFactory() {
        // 不注册任何工厂
        assertFalse(JavaFxPageFactories.isAvailable());

        context.registerJavaFxPage("fx-view", "FX View", proxyJavaFxContent());

        PluginManager.RegisteredPage page = findPage("fx-view");
        assertNotNull(page, "降级路径仍应注册页面（占位内容）");
        assertEquals(PLUGIN_ID, page.pluginId);
        assertEquals("FX View", page.title);
        assertNotNull(page.content, "占位 ComposableContent 不应为 null");
    }

    // ========================================================================
    // 路径 3：参数校验（复用 registerPage 管道）
    // ========================================================================

    @Test
    @DisplayName("null content 抛 NullPointerException")
    void rejectsNullContent() {
        assertThrows(NullPointerException.class,
                () -> context.registerJavaFxPage("fx-view", "FX View", null));
    }

    @Test
    @DisplayName("非法 page id 抛 IllegalArgumentException（复用 registerPage 校验）")
    void rejectsInvalidPageId() {
        assertThrows(IllegalArgumentException.class,
                () -> context.registerJavaFxPage("Bad_ID", "FX View", proxyJavaFxContent()));
    }

    @Test
    @DisplayName("空 title 抛 IllegalArgumentException")
    void rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class,
                () -> context.registerJavaFxPage("fx-view", "  ", proxyJavaFxContent()));
    }

    @Test
    @DisplayName("同一插件内重复 page id 抛 IllegalStateException")
    void rejectsDuplicatePageId() {
        context.registerJavaFxPage("fx-view", "FX View", proxyJavaFxContent());
        assertThrows(IllegalStateException.class,
                () -> context.registerJavaFxPage("fx-view", "FX View Again", proxyJavaFxContent()));
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /** 构造不触及 javafx.scene.Parent 的 JavaFxContent 代理（createRoot 不应被调用） */
    private static JavaFxContent proxyJavaFxContent() {
        return (JavaFxContent) java.lang.reflect.Proxy.newProxyInstance(
                PluginManagerJavaFxPageTest.class.getClassLoader(),
                new Class<?>[]{JavaFxContent.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException(
                            "createRoot 不应在宿主注册路径中被调用，实际调用了: " + method.getName());
                });
    }

    /**
     * 构造最小 PluginEntry 并塞入 loadedPlugins（反射），使 callInPlugin 可以
     * 在插件线程组内执行工厂代码。模拟"插件已加载"状态而无需真实插件 jar。
     */
    @SuppressWarnings("unchecked")
    private void loadFakePluginEntry() throws Exception {
        PluginInfo info = new PluginInfo(
                PLUGIN_ID, "Test FX Plugin", "1.0.0", "tester",
                "JavaFX embedding test plugin", "1.7", "com.example.TestFxPlugin",
                List.of(), "", "", List.of(), null, null, "on-failure", null);
        PluginIsolatingClassLoader classLoader = new PluginIsolatingClassLoader(
                PLUGIN_ID, new URL[0], Thread.currentThread().getContextClassLoader());

        Constructor<PluginManager.PluginEntry> ctor =
                PluginManager.PluginEntry.class.getDeclaredConstructor(
                        PluginInfo.class, com.pmcl.plugin.PmclPlugin.class,
                        PluginManager.PluginContextImpl.class,
                        PluginIsolatingClassLoader.class, Path.class);
        ctor.setAccessible(true);
        PluginManager.PluginEntry entry = ctor.newInstance(
                info, null, context, classLoader, tempDir.resolve("test-fx-plugin.jar"));

        Field field = PluginManager.class.getDeclaredField("loadedPlugins");
        field.setAccessible(true);
        Map<String, PluginManager.PluginEntry> loaded =
                (Map<String, PluginManager.PluginEntry>) field.get(manager);
        loaded.put(PLUGIN_ID, entry);
    }

    private PluginManager.RegisteredPage findPage(String pageId) {
        for (PluginManager.RegisteredPage p : manager.getCustomPages()) {
            if (p.id.equals(pageId) && p.pluginId.equals(PLUGIN_ID)) return p;
        }
        return null;
    }

    private List<PluginManager.RegisteredPage> findPagesByPlugin() {
        List<PluginManager.RegisteredPage> result = new ArrayList<>();
        for (PluginManager.RegisteredPage p : manager.getCustomPages()) {
            if (p.pluginId.equals(PLUGIN_ID)) result.add(p);
        }
        return result;
    }
}
