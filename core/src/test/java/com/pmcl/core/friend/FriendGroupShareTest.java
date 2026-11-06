package com.pmcl.core.friend;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FriendGroupShareTest {

    @Test
    void shareTextRoundTripsNameWithColon() {
        String groupId = UUID.randomUUID().toString();
        String sharer = FriendIdentity.fallback("group-share").toString();
        String text = FriendIdentityManager.groupShareText(groupId, "晚:安", sharer);
        FriendIdentityManager.GroupShare share = FriendIdentityManager.parseGroupShare(text);
        assertEquals(groupId, share.groupId);
        assertEquals("晚:安", share.name);
        assertEquals(sharer, share.sharerId);
    }

    @Test
    void rejectsFriendInviteAndBrokenGroupText() {
        assertNull(FriendIdentityManager.parseGroupShare("pmcl-friend:AAAAA-BBBBB-CCCCC-DDDDD-EEEEE:name"));
        assertNull(FriendIdentityManager.parseGroupShare("pmcl-group:not-a-uuid:name:AAAAA-BBBBB-CCCCC-DDDDD-EEEEE"));
        assertNull(FriendIdentityManager.parseGroupShare(null));
    }

    @Test
    void qrPngDecodesBackToShareText() throws Exception {
        String groupId = UUID.randomUUID().toString();
        String sharer = FriendIdentity.fallback("group-qr").toString();
        String text = FriendIdentityManager.groupShareText(groupId, "生存组", sharer);
        byte[] png = FriendIdentityManager.encodeQrPng(text);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(text, FriendIdentityManager.decodeQrCode(image));
    }
}
