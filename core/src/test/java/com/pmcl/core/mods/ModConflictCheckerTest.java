package com.pmcl.core.mods;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModConflictCheckerTest {

    @Test
    void disabledAndOutOfRangeAreNotConflicts() {
        ModMeta sodium = mod("sodium", "Sodium", "0.6.0", "sodium.jar");
        sodium.setConflictRules(List.of(ModScanner.conflictRule("rubidium", "<0.5.0", false)));

        ModMeta oldRubidium = mod("rubidium", "Rubidium", "0.4.0", "rubidium.jar.disabled");
        ModMeta newRubidium = mod("rubidium", "Rubidium", "0.7.0", "rubidium.jar");

        ModConflictChecker.Result withDisabled = ModConflictChecker.check(List.of(sodium, oldRubidium));
        assertFalse(withDisabled.hasIssues());

        ModConflictChecker.Result newer = ModConflictChecker.check(List.of(sodium, newRubidium));
        assertFalse(newer.hasIssues());

        ModMeta matching = mod("rubidium", "Rubidium", "0.4.1", "rubidium-old.jar");
        ModConflictChecker.Result hit = ModConflictChecker.check(List.of(sodium, matching));
        assertTrue(hit.getErrors().isEmpty());
        assertEquals(1, hit.getWarnings().size());
    }

    @Test
    void hardConflictAnyVersionAndDuplicateEnabledOnly() {
        ModMeta a = mod("optifabric", "OptiFabric", "1.0", "a.jar");
        a.setConflictRules(List.of(ModScanner.conflictRule("optifine", "", true)));
        ModMeta b = mod("optifine", "OptiFine", "HD_U", "b.jar");
        ModConflictChecker.Result both = ModConflictChecker.check(List.of(a, b));
        assertEquals(1, both.getErrors().size());

        ModMeta copy = mod("sodium", "Sodium", "0.5", "sodium-2.jar");
        ModMeta disabledCopy = mod("sodium", "Sodium", "0.4", "sodium.jar.disabled");
        ModConflictChecker.Result dup = ModConflictChecker.check(List.of(
                mod("sodium", "Sodium", "0.6", "sodium.jar"), copy, disabledCopy));
        assertEquals(1, dup.getWarnings().size());
        assertTrue(dup.getWarnings().get(0).contains("重复"));
    }

    @Test
    void versionPredicates() {
        assertTrue(ModConflictChecker.versionMatches("1.2.0", ""));
        assertTrue(ModConflictChecker.versionMatches("1.2.0", "*"));
        assertFalse(ModConflictChecker.versionMatches("0.6.0", "<0.5.0"));
        assertTrue(ModConflictChecker.versionMatches("0.4.9", "<0.5.0"));
        assertTrue(ModConflictChecker.versionMatches("1.20.1", "[1.20,1.21)"));
        assertFalse(ModConflictChecker.versionMatches("1.21.0", "[1.20,1.21)"));
        assertTrue(ModConflictChecker.versionMatches("2.1.0", ">=1.0.0 <3.0.0"));
        assertFalse(ModConflictChecker.versionMatches("3.0.0", ">=1.0.0 <3.0.0"));
    }

    private static ModMeta mod(String id, String name, String version, String jar) {
        return new ModMeta(id, version, name, "", "", "fabric",
                List.of(), List.of(), jar);
    }
}
