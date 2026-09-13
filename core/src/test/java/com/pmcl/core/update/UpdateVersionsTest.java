package com.pmcl.core.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateVersionsTest {

    @Test
    void letterSuffixIsNewerThanBareNumeric() {
        assertTrue(UpdateVersions.isNewer("1.3.0c", "1.3.0"));
        assertFalse(UpdateVersions.isNewer("1.3.0", "1.3.0c"));
    }

    @Test
    void equalVersionsAreNotNewer() {
        assertFalse(UpdateVersions.isNewer("1.3.0", "1.3.0"));
        assertFalse(UpdateVersions.isNewer("1.3.0c", "1.3.0c"));
    }

    @Test
    void higherPatchBeatsLetterSuffix() {
        assertTrue(UpdateVersions.isNewer("1.3.1", "1.3.0c"));
        assertFalse(UpdateVersions.isNewer("1.3.0c", "1.3.1"));
    }

    @Test
    void letterSuffixesCompareLexicographically() {
        assertTrue(UpdateVersions.isNewer("1.3.0c", "1.3.0a"));
        assertFalse(UpdateVersions.isNewer("1.3.0a", "1.3.0c"));
    }

    @Test
    void nullsAreNotNewer() {
        assertFalse(UpdateVersions.isNewer(null, "1.0.0"));
        assertFalse(UpdateVersions.isNewer("1.0.0", null));
    }
}
