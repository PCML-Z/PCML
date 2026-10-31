package com.pmcl.core.compliance;

import com.pmcl.core.auth.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumDownloadsTest {

    @TempDir
    Path home;

    @AfterEach
    void restore() {
        System.clearProperty("pmcl.workdir");
    }

    @Test
    void premiumSourceIsPersistedAndRemovable() throws Exception {
        System.setProperty("pmcl.workdir", home.toString());
        String version = "premium-sourced-" + System.nanoTime();

        assertFalse(PremiumDownloads.isPremiumSourced(version), "初始不应被标记");
        PremiumDownloads.record(version);
        assertTrue(PremiumDownloads.isPremiumSourced(version), "记录后应被标记");

        Path file = home.resolve("premium-downloads.json");
        assertTrue(Files.isRegularFile(file), "应写出标记文件");
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains(version), "文件里应包含该版本: " + json);
        try (var list = Files.list(home)) {
            assertTrue(list.noneMatch(p -> p.getFileName().toString().startsWith("premium-downloads.json.tmp.")),
                    "不应残留临时文件");
        }

        PremiumDownloads.remove(version);
        assertFalse(PremiumDownloads.isPremiumSourced(version), "移除后不应再被标记");
    }

    @Test
    void onlyMicrosoftAccountsCountAsGenuine() {
        assertTrue(new Account("p", "u", "t", Account.AccountType.MICROSOFT).isGenuine());
        assertFalse(new Account("o", "u", "", Account.AccountType.OFFLINE).isGenuine());
        assertFalse(new Account("g", "u", "t", Account.AccountType.GITHUB).isGenuine());
        assertFalse(new Account("y", "u", "t", Account.AccountType.YGGDRASIL).isGenuine());
    }

    @Test
    void genuineAccountLiftsTheRegionalGate() {
        // 正版验证通过时一律放行，不受当前网络判定影响
        assertFalse(DownloadCompliance.isGameDownloadRestrictedFor(true));
        // 未验证时与地区判定保持一致
        assertTrue(DownloadCompliance.isGameDownloadRestrictedFor(false)
                == DownloadCompliance.isGameDownloadRestricted());
    }
}
