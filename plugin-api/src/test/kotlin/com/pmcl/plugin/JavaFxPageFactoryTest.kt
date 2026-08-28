package com.pmcl.plugin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [JavaFxPageFactories] 注册表行为测试。
 *
 * 注册表是 `:ui` → `:core` 的解耦点（同 WebViewPageFactories 模式）：
 * 语义必须是"未注册返回 null / 后注册覆盖 / clear 可复位"，
 * 否则 `registerJavaFxPage` 的降级与包装路径都会出错。
 */
class JavaFxPageFactoryTest {

    @AfterEach
    fun resetRegistry() {
        JavaFxPageFactories.clear()
    }

    @Test
    @DisplayName("初始状态：get 返回 null 且 isAvailable 为 false")
    fun emptyRegistryReturnsNull() {
        assertNull(JavaFxPageFactories.get(), "未注册时 get() 应返回 null")
        assertFalse(JavaFxPageFactories.isAvailable(), "未注册时 isAvailable() 应为 false")
    }

    @Test
    @DisplayName("register 后 get 返回同一实例且 isAvailable 为 true")
    fun registerMakesFactoryAvailable() {
        val factory = JavaFxPageFactory { _ ->
            throw UnsupportedOperationException("不应在此测试中被调用")
        }
        JavaFxPageFactories.register(factory)
        assertSame(factory, JavaFxPageFactories.get(), "get() 应返回刚注册的工厂实例")
        assertTrue(JavaFxPageFactories.isAvailable(), "注册后 isAvailable() 应为 true")
    }

    @Test
    @DisplayName("重复注册以最后一次为准（宿主重启 UI 实现时覆盖旧工厂）")
    fun reRegisterReplacesPreviousFactory() {
        val first = JavaFxPageFactory { _ ->
            throw UnsupportedOperationException("first")
        }
        val second = JavaFxPageFactory { _ ->
            throw UnsupportedOperationException("second")
        }
        JavaFxPageFactories.register(first)
        JavaFxPageFactories.register(second)
        assertSame(second, JavaFxPageFactories.get(), "重复注册后应返回最后一次注册的工厂")
    }

    @Test
    @DisplayName("clear 复位注册表（headless 测试间互不污染）")
    fun clearResetsRegistry() {
        JavaFxPageFactories.register(JavaFxPageFactory { _ ->
            throw UnsupportedOperationException("不应被调用")
        })
        JavaFxPageFactories.clear()
        assertNull(JavaFxPageFactories.get(), "clear 后 get() 应返回 null")
        assertFalse(JavaFxPageFactories.isAvailable(), "clear 后 isAvailable() 应为 false")
    }

    @Test
    @DisplayName("unavailableContent 返回可用的 ComposableContent 占位实例")
    fun unavailableContentReturnsUsableInstance() {
        val a = JavaFxPageFactories.unavailableContent()
        val b = JavaFxPageFactories.unavailableContent()
        // headless 降级页由 SafePluginPage 捕获异常渲染；这里只验证契约：
        // 非 null 且可重复获取（实例是否单例是实现细节——无捕获 lambda 可能被
        // Kotlin 编译器单例化，不构成状态共享问题）
        assertTrue(a != null, "unavailableContent() 不应返回 null")
        assertTrue(b != null, "unavailableContent() 可重复获取")
    }

    @Test
    @DisplayName("JavaFxContent 实例可在未初始化 JavaFX toolkit 的环境创建（懒加载契约）")
    fun javaFxContentInstanceWithoutToolkit() {
        // core（无 UI 宿主）只需持有/传递 JavaFxContent 引用，不调用 createRoot。
        // 验证：创建 lambda 实例不触发 toolkit 初始化，也不会因缺少运行时失败。
        val content: JavaFxContent = JavaFxContent {
            throw IllegalStateException("createRoot 不应在此测试中被调用")
        }
        assertTrue(content != null, "JavaFxContent 实例应可正常创建")
        // 二次确认：同一实例可被工厂安全传递
        var received: JavaFxContent? = null
        val factory = JavaFxPageFactory { c ->
            received = c
            throw UnsupportedOperationException("工厂只需记录参数")
        }
        try {
            factory.create(content)
        } catch (_: UnsupportedOperationException) {
            // 预期：本测试的工厂只记录引用后抛出
        }
        assertSame(content, received, "工厂应原样收到插件提供的 JavaFxContent 实例")
    }
}
