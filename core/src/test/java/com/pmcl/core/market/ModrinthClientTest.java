package com.pmcl.core.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModrinthClientTest {

    @Test
    void searchUsesIndexValues() {
        assertEquals("relevance", ModrinthClient.searchIndexParam("default", false));
        assertEquals("downloads", ModrinthClient.searchIndexParam("default", true));
        assertEquals("downloads", ModrinthClient.searchIndexParam("downloads", false));
        assertEquals("updated", ModrinthClient.searchIndexParam("updated", false));
        assertEquals("newest", ModrinthClient.searchIndexParam("newest", false));
        assertEquals("downloads", ModrinthClient.searchIndexParam("relevance", true));
        assertEquals("relevance", ModrinthClient.searchIndexParam("relevance", false));
    }
}
