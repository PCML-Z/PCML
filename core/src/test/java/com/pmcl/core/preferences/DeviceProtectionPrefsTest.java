package com.pmcl.core.preferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceProtectionPrefsTest {

    @TempDir
    Path tmp;

    @Test
    void wipingLicenseDoesNotClearEnforcedFlag() {
        Preferences p = new Preferences(tmp.resolve("prefs.json"));
        p.setDeviceProtectionEnforced(true);
        p.setDeviceProtectionLicense("");
        p.setDeviceProtectionPublicKey("");
        assertTrue(p.shouldEnforceDeviceProtection());
        assertFalse(p.isDeviceProtectionEnabled());
    }

    @Test
    void officialDisableClearsEnforcedFlag() {
        Preferences p = new Preferences(tmp.resolve("prefs.json"));
        p.setDeviceProtectionEnforced(true);
        p.setDeviceProtectionEnforced(false);
        assertFalse(p.shouldEnforceDeviceProtection());
    }
}
