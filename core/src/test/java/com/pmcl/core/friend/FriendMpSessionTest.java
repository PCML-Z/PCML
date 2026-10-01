package com.pmcl.core.friend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendMpSessionTest {

    @Test
    void rejectsLoopbackAndKeepsInvite() {
        FriendProtocol.MpSession raw = new FriendProtocol.MpSession();
        raw.backend = "easytier";
        raw.invitation = "pmcl2-abc";
        raw.mcHost = "127.0.0.1";
        raw.mcPort = 25565;
        FriendProtocol.MpSession clean = FriendProtocol.MpSession.sanitize(raw, "friend-1");
        assertEquals("EASYTIER", clean.backend);
        assertEquals("pmcl2-abc", clean.invitation);
        assertEquals("", clean.mcHost);
        assertEquals(0, clean.mcPort);
        assertEquals("friend-1", clean.from);
    }

    @Test
    void acceptsVirtualAddress() {
        assertTrue(FriendProtocol.MpSession.isShareableHost("10.0.0.8"));
        assertFalse(FriendProtocol.MpSession.isShareableHost("localhost"));
        FriendProtocol.MpSession raw = new FriendProtocol.MpSession();
        raw.backend = "CONNECTX";
        raw.invitation = "connectx-room";
        raw.mcHost = "10.0.0.8";
        raw.mcPort = 43210;
        FriendProtocol.MpSession clean = FriendProtocol.MpSession.sanitize(raw, "friend-2");
        assertEquals("10.0.0.8", clean.mcHost);
        assertEquals(43210, clean.mcPort);
    }

    @Test
    void rejectsUnknownBackend() {
        FriendProtocol.MpSession raw = new FriendProtocol.MpSession();
        raw.backend = "OTHER";
        raw.invitation = "x";
        assertNull(FriendProtocol.MpSession.sanitize(raw, "friend-3"));
    }
}
