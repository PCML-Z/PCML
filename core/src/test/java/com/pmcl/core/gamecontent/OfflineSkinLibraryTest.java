package com.pmcl.core.gamecontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineSkinLibraryTest {

    @TempDir
    Path tempDir;

    @Test
    void importsASkinAndDeploysItWithoutDroppingExistingProfiles() throws Exception {
        Path png = writePng(tempDir.resolve("alex.png"), 64, 64);
        OfflineSkinLibrary library = new OfflineSkinLibrary(tempDir);
        OfflineSkinLibrary.Skin imported = library.importPng(png, "Alex");
        library.update(imported.id(), "Alex", "Alex", "slim");

        Path game = tempDir.resolve("game");
        Files.createDirectories(game.resolve("CustomSkinLoader"));
        Files.writeString(game.resolve("CustomSkinLoader/CustomSkinLoader.json"), """
                {"version":"14.16","loadlist":[{"name":"Mojang","type":"MojangAPI"}]}
                """);

        OfflineSkinLibrary.DeployResult result = library.deploy(game);
        assertEquals(1, result.written());
        assertTrue(result.profileAdded());
        assertTrue(Files.exists(game.resolve("CustomSkinLoader/LocalSkin/skins/Alex.png")));
        String meta = Files.readString(game.resolve("CustomSkinLoader/LocalSkin/skins/Alex.json"));
        assertTrue(meta.contains("slim"));
        String config = Files.readString(game.resolve("CustomSkinLoader/CustomSkinLoader.json"));
        assertTrue(config.indexOf("LocalSkin") < config.indexOf("Mojang"));
        assertTrue(config.contains("MojangAPI"));

        result = library.deploy(game);
        assertFalse(result.profileAdded());
        assertTrue(result.profileKept());
        assertEquals(1, result.written());
    }

    @Test
    void rejectsTheWrongSizeADuplicatePlayerAndAPathPlayer() throws Exception {
        OfflineSkinLibrary library = new OfflineSkinLibrary(tempDir);
        Path tiny = writePng(tempDir.resolve("tiny.png"), 32, 32);
        OfflineSkinLibrary.Failure size = assertThrows(
                OfflineSkinLibrary.Failure.class, () -> library.importPng(tiny, "Tiny"));
        assertEquals(OfflineSkinLibrary.Failure.Code.BAD_SIZE, size.code());

        Path png = writePng(tempDir.resolve("steve.png"), 64, 32);
        OfflineSkinLibrary.Skin first = library.importPng(png, "Steve");
        library.update(first.id(), "Steve", "Steve", "classic");
        OfflineSkinLibrary.Skin second = library.importPng(png, "Other");
        OfflineSkinLibrary.Failure duplicate = assertThrows(
                OfflineSkinLibrary.Failure.class,
                () -> library.update(second.id(), "Other", "steve", "classic"));
        assertEquals(OfflineSkinLibrary.Failure.Code.DUPLICATE_PLAYER, duplicate.code());

        OfflineSkinLibrary.Failure path = assertThrows(
                OfflineSkinLibrary.Failure.class,
                () -> library.update(second.id(), "Other", "../Steve", "classic"));
        assertEquals(OfflineSkinLibrary.Failure.Code.BAD_PLAYER, path.code());
    }

    @Test
    void leavesAnUnreadableConfigUntouched() throws Exception {
        Path png = writePng(tempDir.resolve("kai.png"), 64, 64);
        OfflineSkinLibrary library = new OfflineSkinLibrary(tempDir);
        OfflineSkinLibrary.Skin imported = library.importPng(png, "Kai");
        library.update(imported.id(), "Kai", "Kai", "classic");

        Path game = tempDir.resolve("game");
        Path config = game.resolve("CustomSkinLoader/CustomSkinLoader.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "not-json");

        OfflineSkinLibrary.Failure failure = assertThrows(
                OfflineSkinLibrary.Failure.class, () -> library.deploy(game));
        assertEquals(OfflineSkinLibrary.Failure.Code.CONFIG_UNREADABLE, failure.code());
        assertEquals("not-json", Files.readString(config));
    }

    private static Path writePng(Path file, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}
