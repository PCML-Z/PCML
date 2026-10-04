package com.pmcl.core.multiplayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiddenServerAddressTest {

    @Test
    void plainTokenUsesOfficialShorthand() {
        assertEquals("room@play.example.com",
                HiddenServerAddress.clientArg("play.example.com", "room"));
        assertEquals("play.example.com?_id=room",
                HiddenServerAddress.packetHost("play.example.com", "room"));
        assertEquals("play.example.com",
                HiddenServerAddress.connectHost("room@play.example.com"));
    }

    @Test
    void reservedCharactersArePercentEncoded() {
        assertEquals("play.example.com?_id=a%20b%26c%3D",
                HiddenServerAddress.packetHost("play.example.com", "a b&c="));
        assertEquals("play.example.com?_id=a%20b%26c%3D",
                HiddenServerAddress.clientArg("play.example.com", "a b&c="));
        assertEquals("a b&c=", HiddenServerAddress.cleanToken(
                HiddenServerAddress.packetHost("play.example.com", "a b&c=").substring("play.example.com?_id=".length())
                        .replace("%20", " ").replace("%26", "&").replace("%3D", "=")));
    }

    @Test
    void pastedAddressIsNotDoubled() {
        assertEquals("room@play.example.com",
                HiddenServerAddress.clientArg("room@play.example.com", ""));
        assertEquals("play.example.com?_id=room",
                HiddenServerAddress.packetHost("room@play.example.com:25566", ""));
        assertEquals("play.example.com",
                HiddenServerAddress.connectHost("play.example.com?_id=room"));
    }

    @Test
    void explicitTokenReplacesPastedId() {
        assertEquals("next@play.example.com",
                HiddenServerAddress.clientArg("old@play.example.com", "next"));
    }

    @Test
    void portStaysOutsideTheQuery() {
        assertEquals("room@play.example.com:25566",
                HiddenServerAddress.serversDatAddress("play.example.com", 25566, "room"));
        assertEquals("play.example.com:25566?_id=a%20b",
                HiddenServerAddress.serversDatAddress("play.example.com", 25566, "a b"));
        assertEquals("room@[::1]:25566",
                HiddenServerAddress.serversDatAddress("::1", 25566, "room"));
    }

    @Test
    void emptyTokenLeavesTheHostAlone() {
        assertEquals("play.example.com", HiddenServerAddress.clientArg("play.example.com", ""));
        assertEquals("play.example.com", HiddenServerAddress.packetHost("play.example.com", "   "));
    }

    @Test
    void oversizedTokenIsNotSentInTheHandshake() {
        String host = "a".repeat(253);
        assertEquals(host, HiddenServerAddress.packetHost(host, "测".repeat(256)));
    }

    @Test
    void redactHidesTheToken() {
        assertEquals("***@play.example.com", HiddenServerAddress.redact("room@play.example.com"));
        assertEquals("play.example.com?_id=***",
                HiddenServerAddress.redact("play.example.com?_id=a%20b"));
    }
}
