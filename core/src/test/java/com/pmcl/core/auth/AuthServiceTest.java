package com.pmcl.core.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    @Test
    void sameAccessTokenIsNotReusedAfter401() {
        long future = System.currentTimeMillis() + 3_600_000L;
        Account caller = ms("A2", "rt", future);
        Account cached = ms("A2", "rt", future);
        assertFalse(AuthService.canReuseMsRefreshCache(caller, cached, System.currentTimeMillis()));
    }

    @Test
    void concurrentWaiterReusesRotatedAccessToken() {
        long future = System.currentTimeMillis() + 3_600_000L;
        Account caller = ms("A1", "old-rt", future);
        Account cached = ms("A2", "new-rt", future);
        assertTrue(AuthService.canReuseMsRefreshCache(caller, cached, System.currentTimeMillis()));
    }

    @Test
    void expiredCacheIsNotReused() {
        long past = System.currentTimeMillis() - 1_000L;
        Account caller = ms("A1", "rt", past);
        Account cached = ms("A2", "rt", past);
        assertFalse(AuthService.canReuseMsRefreshCache(caller, cached, System.currentTimeMillis()));
    }

    @Test
    void corruptAccountFileIsKeptAndNotTreatedAsEmpty() throws Exception {
        Path dir = Files.createTempDirectory("pmcl-accounts-corrupt");
        Path file = dir.resolve("accounts.json");
        String raw = "{\"accounts\":[";
        Files.writeString(file, raw);
        AuthService auth = new AuthService();
        assertThrows(java.io.IOException.class, () -> auth.loadStore(file));
        assertEquals(raw, Files.readString(file));
        try (var listed = Files.list(dir)) {
            assertTrue(listed.anyMatch(p -> p.getFileName().toString().startsWith("accounts.json.corrupt.")));
        }
    }

    @Test
    void offlineAccountRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("pmcl-accounts-round");
        Path file = dir.resolve("accounts.json");
        AuthService auth = new AuthService();
        Account account = auth.offline("Steve");
        auth.saveStore(new AccountStore(List.of(account), account.getUuid()), file);
        AccountStore loaded = auth.loadStore(file);
        assertEquals(1, loaded.getAccounts().size());
        assertEquals("Steve", loaded.getAccounts().get(0).getUsername());
    }

    private static Account ms(String access, String refresh, long expiresAt) {
        return new Account("u", "uuid", access, Account.AccountType.MICROSOFT,
                "", "classic", "", "", refresh, expiresAt);
    }
}
