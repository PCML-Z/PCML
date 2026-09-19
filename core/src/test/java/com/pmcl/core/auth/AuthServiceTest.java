package com.pmcl.core.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static Account ms(String access, String refresh, long expiresAt) {
        return new Account("u", "uuid", access, Account.AccountType.MICROSOFT,
                "", "classic", "", "", refresh, expiresAt);
    }
}
