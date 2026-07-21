package com.pmcl.core.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件隔离 ClassLoader。
 * <p>
 * 在标准 URLClassLoader 之上增加包名白名单过滤，阻止插件直接加载 PMCL 内部类
 * （如 AuthService、Preferences、LaunchManager 的私有实现），强制插件通过
 * {@code PluginContext.getService} 获取受控的服务引用。
 * <p>
 * 允许加载的包：
 * <ul>
 *   <li>{@code com.pmcl.plugin.*} — 插件 API 公共接口（必须可访问）</li>
 *   <li>{@code androidx.compose.*} — Compose 运行时（插件 UI 页面需要）</li>
 *   <li>{@code kotlin.*} / {@code kotlinx.*} — Kotlin 标准库与协程</li>
 *   <li>{@code java.*} / {@code javax.*} / {@code sun.*} — JDK 标准类</li>
 *   <li>插件自身的包（由 URLClassLoader 父类处理）</li>
 *   <li>第三方依赖包（org.jetbrains.annotations / org.intellij / com.google.gson 等公共 API）</li>
 * </ul>
 * <p>
 * 被阻止的包：
 * <ul>
 *   <li>{@code com.pmcl.core.auth.*} — 含 token / 账号凭据</li>
 *   <li>{@code com.pmcl.core.preferences.*} — 含代理密码等配置</li>
 *   <li>{@code com.pmcl.core.launch.*} — 含启动命令构造</li>
 *   <li>{@code com.pmcl.core.update.*} — 含自更新逻辑</li>
 *   <li>其他 {@code com.pmcl.core.*} 子包——必须通过 getService 获取</li>
 * </ul>
 * <p>
 * 例外：插件通过 getService 获取的服务实例本身可以被反射访问，这是受控的——
 * 我们在 getService 层做权限校验，而非依赖 ClassLoader 完全隔离。
 * <p>
 * 安全说明：这是深度防御层。恶意插件仍可通过 Thread.currentThread().getContextClassLoader()
 * 或反射 sun.misc.Unsafe 绕过，需要 SecurityManager 才能彻底隔离。但对于"无意中"
 * 访问敏感类的插件，本类提供有效的防护。
 */
public final class PluginIsolatingClassLoader extends URLClassLoader {

    /** 允许插件直接加载的 PMCL 包前缀。 */
    private static final String[] ALLOWED_PMCL_PREFIXES = {
        "com.pmcl.plugin.",          // 插件 API 公共接口
    };

    /** 允许插件直接加载的第三方包前缀。 */
    private static final String[] ALLOWED_THIRD_PARTY_PREFIXES = {
        "androidx.compose.",
        "kotlin.",
        "kotlinx.",
        "org.jetbrains.annotations.",
        "org.intellij.lang.annotations.",
        "com.google.gson.",
        "org.slf4j.",
        "java.",
        "javax.",
        "sun.",
        "jdk.",
    };

    /** 完全禁止的 PMCL core 包前缀（即使通过反射也不允许）。 */
    private static final String[] FORBIDDEN_PMCL_PREFIXES = {
        "com.pmcl.core.auth.",
        "com.pmcl.core.preferences.",
        "com.pmcl.core.update.",
    };

    private final String pluginId;

    public PluginIsolatingClassLoader(String pluginId, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.pluginId = pluginId;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 1. 检查是否为禁止的敏感类
        if (isForbidden(name)) {
            throw new SecurityException("[Plugin:" + pluginId
                    + "] 直接加载 PMCL 内部类被禁止: " + name
                    + "（请通过 PluginContext.getService 获取受控引用）");
        }

        // 2. 允许的类走标准双亲委派
        if (isAllowed(name)) {
            return super.loadClass(name, resolve);
        }

        // 3. 其他 com.pmcl.* 类阻止（强制走 getService）
        if (name.startsWith("com.pmcl.")) {
            throw new SecurityException("[Plugin:" + pluginId
                    + "] 直接加载 PMCL 内部类被禁止: " + name
                    + "（请通过 PluginContext.getService 获取受控引用）");
        }

        // 4. 非 PMCL 类（插件自身 / 第三方依赖）正常加载
        return super.loadClass(name, resolve);
    }

    /** 判断类是否为禁止的敏感类（即使整体阻止 com.pmcl.* 也单独标记，便于日志清晰）。 */
    private boolean isForbidden(String name) {
        for (String prefix : FORBIDDEN_PMCL_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 判断类是否为允许直接加载的类。 */
    private boolean isAllowed(String name) {
        for (String prefix : ALLOWED_PMCL_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        for (String prefix : ALLOWED_THIRD_PARTY_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
