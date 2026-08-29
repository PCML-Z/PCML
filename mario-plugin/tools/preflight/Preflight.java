import com.pmcl.plugin.PluginInfo;
import com.pmcl.plugin.PmclPlugin;
import com.pmcl.plugin.PluginPermission;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 装机前自检：
 * 1. 用 plugin-api 自己的校验器核对描述符，确认主类能被加载实例化；
 * 2. 检查 jarsigner 签名块（META-INF/*.SF|*.RSA|*.DSA|*.EC）——PMCL 的 loadPlugin
 *    对 JAR 强制验签(verifyPluginArchive)，未签名的 jar 会被 SecurityException 拒绝；
 * 3. 检查签名的关键 entry(.class / pmcl-plugin.properties) 是否带 CodeSigner。
 */
public class Preflight {

    static int bad = 0;

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath().normalize();
        System.out.println("jar = " + jar + "  (" + Files.size(jar) + " bytes)");

        // 签名检查先行：未签名的 jar 即使描述符/主类都合法，宿主也会拒绝加载
        checkSignature(jar);

        Properties props = new Properties();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, Preflight.class.getClassLoader())) {
            try (InputStream in = cl.getResourceAsStream("META-INF/pmcl-plugin.properties")) {
                if (in == null) { fail("缺少 META-INF/pmcl-plugin.properties"); return; }
                props.load(in);
            }

            String id = props.getProperty("plugin.id", "");
            String name = props.getProperty("plugin.name", "");
            String version = props.getProperty("plugin.version", "");
            String author = props.getProperty("plugin.author", "");
            String desc = props.getProperty("plugin.description", "");
            String api = props.getProperty("plugin.api-version", "");
            String main = props.getProperty("plugin.main-class", "");
            String perms = props.getProperty("plugin.permissions", "");

            System.out.println("  id=" + id + "  version=" + version + "  api=" + api);
            System.out.println("  main-class=" + main);

            if (!PluginInfo.isValidId(id)) fail("plugin.id 不合法: " + id);
            if (!PluginInfo.isValidVersion(version)) fail("plugin.version 不合法: " + version);
            if (!PluginInfo.isValidMainClass(main)) fail("plugin.main-class 不合法: " + main);
            if (name.isBlank() || name.length() > PluginInfo.NAME_MAX_LEN) fail("plugin.name 不合法");
            if (author.isBlank() || author.length() > PluginInfo.AUTHOR_MAX_LEN) fail("plugin.author 不合法");
            if (desc.isBlank() || desc.length() > PluginInfo.DESCRIPTION_MAX_LEN) fail("plugin.description 不合法");

            Set<String> supported = PluginInfo.Companion.getSUPPORTED_API_VERSIONS();
            if (!supported.contains(api)) fail("plugin.api-version " + api + " 不在支持列表 " + supported);

            for (String p : perms.split(",")) {
                if (!p.isBlank() && PluginPermission.Companion.parseOrNull(p) == null) fail("未知权限: " + p);
            }

            // 主类可加载 + 可实例化 + pluginId 与描述符一致
            Class<?> c = cl.loadClass(main);
            Object inst = c.getDeclaredConstructor().newInstance();
            if (!(inst instanceof PmclPlugin)) { fail(main + " 没有实现 PmclPlugin"); return; }
            String runtimeId = ((PmclPlugin) inst).getPluginId();
            System.out.println("  主类加载 OK，运行时 pluginId=" + runtimeId);
            if (!runtimeId.equals(id)) fail("运行时 pluginId(" + runtimeId + ") 与描述符(" + id + ") 不一致");
        }

        System.out.println(bad == 0 ? "\nPREFLIGHT OK —— 描述符与主类都通过" : "\nPREFLIGHT FAILED (" + bad + ")");
        if (bad > 0) System.exit(1);
    }

    /** 检查 jarsigner 签名块 + 关键 entry 的 CodeSigner（复刻 PluginManager.verifyPluginArchive 的核心检查）。 */
    static void checkSignature(Path jar) throws Exception {
        boolean hasSigBlock = false;
        try (JarFile jf = new JarFile(jar.toFile(), true)) {
            Enumeration<JarEntry> en = jf.entries();
            int unsignedCritical = 0;
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("META-INF/")) {
                    if (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")) {
                        hasSigBlock = true;
                    }
                    continue;
                }
                // 读取以触发摘要校验（verify=true 时读取会抛 SecurityException）
                try (InputStream in = jf.getInputStream(e)) {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                }
                boolean critical = n.endsWith(".class") || n.equals("META-INF/pmcl-plugin.properties");
                if (critical && (e.getCodeSigners() == null || e.getCodeSigners().length == 0)) {
                    unsignedCritical++;
                }
            }
            if (!hasSigBlock) {
                fail("jar 无签名块(META-INF/*.SF|RSA|DSA|EC)——PMCL 会拒绝: Plugin JAR is not signed");
            } else if (unsignedCritical > 0) {
                fail("有 " + unsignedCritical + " 个关键 entry(.class/描述符) 未被签名覆盖");
            } else if (hasSigBlock) {
                System.out.println("  签名检查 OK：存在签名块，关键 entry 均带 CodeSigner");
            }
        } catch (SecurityException se) {
            fail("jar 签名摘要校验失败(内容被篡改?): " + se.getMessage());
        }
    }

    static void fail(String m) { bad++; System.out.println("  !! " + m); }
}
