package com.pmcl.core.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmclPluginStoreClientTest {

    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void readsPluginsJsWithoutExecutingIt() throws Exception {
        String script = """
                // not executed
                var PMCL_PLUGINS = [
                  {"id":"ok","name":"好插件]","version":"1.2.0","author":"Lash","summary":"简介",
                   "description":"说明","downloads":3,"updatedAt":"2026-09-26T00:00:00Z",
                   "iconUrl":"https://evil.example/icon.png",
                   "homepage":"https://lash.org.cn/pmcl/",
                   "packageType":"jar",
                   "downloadUrl":"https://lash.org.cn/pmcl/files/ok/ok-1.2.0.jar",
                   "sha256":"%s","size":10},
                  {"id":"bad","name":"坏","version":"1","author":"x","summary":"","description":"",
                   "downloads":0,"packageType":"jar",
                   "downloadUrl":"https://evil.example/a.jar","sha256":"%s"}
                ];
                alert("no");
                """.formatted(SHA, SHA);
        java.util.List<StorePlugin> plugins = PmclPluginStoreClient.parseCatalog(script);
        assertEquals(1, plugins.size());
        StorePlugin plugin = plugins.get(0);
        assertEquals("ok", plugin.id);
        assertEquals("好插件]", plugin.name);
        assertEquals("", plugin.iconUrl);
        assertEquals("https://lash.org.cn/pmcl/", plugin.homepage);
        assertEquals(SHA, plugin.sha256);
    }

    @Test
    void storeHostAllowsOnlyLashHttps() {
        assertNull(PmclPluginStoreClient.storeHostError("https://lash.org.cn/plugins/a.jar"));
        assertNull(PmclPluginStoreClient.storeHostError("https://www.lash.org.cn/plugins/a.jar"));
        assertNotNull(PmclPluginStoreClient.storeHostError("http://lash.org.cn/a.jar"));
        assertNotNull(PmclPluginStoreClient.storeHostError("https://lash.org.cn.evil.com/a.jar"));
        assertNotNull(PmclPluginStoreClient.storeHostError("https://user:pass@lash.org.cn/a.jar"));
        assertNotNull(PmclPluginStoreClient.storeHostError("https://lash.org.cn:8443/a.jar"));
        assertTrue(PmclPluginStoreClient.storeHostError("https://evil.example/a.jar").contains("lash.org.cn"));
    }
}
