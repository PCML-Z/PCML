package com.pmcl.core.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmclDeveloperCertificationTest {

    @Test
    void pluginTopicCertifiesOwnerRepo() throws Exception {
        assertTrue(PmclDeveloperCertification.matches("""
                [{"name":"demo","fork":false,"topics":["minecraft","pmcl-plugin"]}]
                """));
    }

    @Test
    void ecxpTopicCertifiesOwnerRepo() throws Exception {
        assertTrue(PmclDeveloperCertification.matches("""
                [{"name":"watch","fork":false,"topics":["ECXP-APP"]}]
                """));
    }

    @Test
    void forkAndOtherTopicsDoNotCertify() throws Exception {
        assertFalse(PmclDeveloperCertification.matches("""
                [
                  {"name":"forked","fork":true,"topics":["pmcl-plugin"]},
                  {"name":"other","fork":false,"topics":["pmcl","ecxp"]}
                ]
                """));
        assertEquals(2, PmclDeveloperCertification.repoCount("""
                [{"name":"a"},{"name":"b"}]
                """));
    }

    @Test
    void loginMustBeAGitHubName() throws Exception {
        assertThrows(IOException.class, () -> PmclDeveloperCertification.requireLogin("a/b"));
        assertThrows(IOException.class, () -> PmclDeveloperCertification.requireLogin(""));
        assertThrows(IOException.class, () -> PmclDeveloperCertification.requireLogin("has space"));
        assertEquals("peddlejumper", PmclDeveloperCertification.requireLogin(" peddlejumper "));
    }
}
