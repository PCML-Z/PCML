package com.pmcl.core.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CurseForgeKeyTest {

    @Test
    void normalizeTrimsBearerAndRejectsWhitespace() {
        assertEquals("", ModMarketManager.normalizeCurseForgeKey("  "));
        assertEquals("abc123", ModMarketManager.normalizeCurseForgeKey("Bearer abc123"));
        assertNull(ModMarketManager.normalizeCurseForgeKey("bad key"));
        assertNull(ModMarketManager.normalizeCurseForgeKey("a".repeat(201)));
    }
}
