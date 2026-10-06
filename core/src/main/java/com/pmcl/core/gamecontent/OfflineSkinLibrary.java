package com.pmcl.core.gamecontent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 离线皮肤库。皮肤 PNG 存在启动器目录，绑定玩家名后可写入游戏目录，
 * 供 CustomSkinLoader 的 LocalSkin 读取。
 */
public final class OfflineSkinLibrary {

    private static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PLAYER = Pattern.compile("[\\p{L}\\p{N}_]{1,16}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path root;
    private final Path filesDir;
    private final Path indexFile;
    private final Object lock = new Object();

    public OfflineSkinLibrary(Path pmclHome) {
        this.root = pmclHome.resolve("skins");
        this.filesDir = root.resolve("files");
        this.indexFile = root.resolve("index.json");
    }

    public Path directory() throws IOException {
        Files.createDirectories(filesDir);
        return root;
    }

    public List<Skin> list() throws IOException {
        synchronized (lock) {
            List<Skin> skins = new ArrayList<>(load());
            skins.sort(Comparator.comparing(skin -> skin.name.toLowerCase(Locale.ROOT)));
            return List.copyOf(skins);
        }
    }

    public Path pngFile(Skin skin) {
        if (skin == null || !ID.matcher(skin.id).matches()) {
            throw new Failure(Failure.Code.NOT_FOUND, "");
        }
        return filesDir.resolve(skin.id + ".png");
    }

    public Skin importPng(Path source, String displayName) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new Failure(Failure.Code.NOT_PNG, "");
        }
        validatePng(source);
        String name = cleanName(displayName);
        synchronized (lock) {
            Files.createDirectories(filesDir);
            Stored stored = new Stored();
            stored.id = UUID.randomUUID().toString().replace("-", "");
            stored.name = name;
            stored.player = "";
            stored.model = "classic";
            stored.addedAt = System.currentTimeMillis();
            Path dest = filesDir.resolve(stored.id + ".png");
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            List<Skin> skins = new ArrayList<>(load());
            Skin created = toSkin(stored);
            skins.add(created);
            save(skins);
            return created;
        }
    }

    public void delete(String id) throws IOException {
        String safe = requireId(id);
        synchronized (lock) {
            List<Skin> skins = new ArrayList<>(load());
            boolean removed = skins.removeIf(skin -> skin.id.equals(safe));
            if (!removed) throw new Failure(Failure.Code.NOT_FOUND, "");
            save(skins);
            Files.deleteIfExists(filesDir.resolve(safe + ".png"));
        }
    }

    public Skin update(String id, String displayName, String player, String model) throws IOException {
        String safe = requireId(id);
        String name = cleanName(displayName);
        String bound = cleanPlayer(player);
        String slim = cleanModel(model);
        synchronized (lock) {
            List<Skin> skins = new ArrayList<>(load());
            Skin current = null;
            for (Skin skin : skins) {
                if (skin.id.equals(safe)) current = skin;
            }
            if (current == null) throw new Failure(Failure.Code.NOT_FOUND, "");
            if (!bound.isEmpty()) {
                for (Skin skin : skins) {
                    if (!skin.id.equals(safe) && skin.player.equalsIgnoreCase(bound)) {
                        throw new Failure(Failure.Code.DUPLICATE_PLAYER, bound);
                    }
                }
            }
            Skin updated = new Skin(safe, name, bound, slim, current.addedAt);
            skins.replaceAll(skin -> skin.id.equals(safe) ? updated : skin);
            save(skins);
            return updated;
        }
    }

    /**
     * 把已绑定玩家名的皮肤写入游戏目录，并在缺少时补上 LocalSkin 配置。
     * 已有的 LocalSkin 条目保持原样。
     */
    public DeployResult deploy(Path gameDir) throws IOException {
        if (gameDir == null) throw new Failure(Failure.Code.NO_GAME_DIR, "");
        Path rootDir = gameDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(rootDir)) {
            Path parent = rootDir.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                Files.createDirectories(rootDir);
            } else {
                throw new Failure(Failure.Code.NO_GAME_DIR, "");
            }
        }
        Path skinsDir = rootDir.resolve("CustomSkinLoader").resolve("LocalSkin").resolve("skins").normalize();
        if (!skinsDir.startsWith(rootDir)) throw new Failure(Failure.Code.NO_GAME_DIR, "");
        List<Skin> bound;
        synchronized (lock) {
            bound = load().stream().filter(skin -> !skin.player.isEmpty()).toList();
        }
        if (bound.isEmpty()) return new DeployResult(0, false, false);
        Files.createDirectories(skinsDir);
        for (Skin skin : bound) {
            Path png = skinsDir.resolve(skin.player + ".png").normalize();
            Path meta = skinsDir.resolve(skin.player + ".json").normalize();
            if (!png.startsWith(skinsDir) || !meta.startsWith(skinsDir)) {
                throw new Failure(Failure.Code.BAD_PLAYER, skin.player);
            }
            Path source = pngFile(skin);
            if (!Files.isRegularFile(source)) throw new Failure(Failure.Code.NOT_FOUND, skin.id);
            Files.copy(source, png, StandardCopyOption.REPLACE_EXISTING);
            String model = "slim".equals(skin.model) ? "slim" : "default";
            writeAtomic(meta, "{\"model\":\"" + model + "\"}\n");
        }
        boolean added = ensureLocalSkinProfile(rootDir);
        return new DeployResult(bound.size(), added, !added);
    }

    private boolean ensureLocalSkinProfile(Path gameDir) throws IOException {
        Path config = gameDir.resolve("CustomSkinLoader").resolve("CustomSkinLoader.json");
        if (Files.isSymbolicLink(config)) throw new Failure(Failure.Code.CONFIG_UNREADABLE, "");
        JsonObject root;
        if (Files.isRegularFile(config)) {
            try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) throw new Failure(Failure.Code.CONFIG_UNREADABLE, "");
                root = parsed.getAsJsonObject();
            } catch (Failure e) {
                throw e;
            } catch (RuntimeException | IOException e) {
                throw new Failure(Failure.Code.CONFIG_UNREADABLE, "");
            }
        } else {
            root = new JsonObject();
            root.addProperty("version", "14.16");
        }
        JsonArray loadlist = root.getAsJsonArray("loadlist");
        if (loadlist == null) {
            loadlist = new JsonArray();
            root.add("loadlist", loadlist);
        }
        for (JsonElement element : loadlist) {
            if (!element.isJsonObject()) continue;
            JsonElement name = element.getAsJsonObject().get("name");
            if (name != null && name.isJsonPrimitive() && "LocalSkin".equals(name.getAsString())) {
                return false;
            }
        }
        JsonArray updated = new JsonArray();
        updated.add(localSkinProfile());
        for (JsonElement element : loadlist) updated.add(element);
        root.add("loadlist", updated);
        writeAtomic(config, GSON.toJson(root) + "\n");
        return true;
    }

    private static JsonObject localSkinProfile() {
        JsonObject profile = new JsonObject();
        profile.addProperty("name", "LocalSkin");
        profile.addProperty("type", "Legacy");
        profile.addProperty("checkPNG", false);
        profile.addProperty("model", "auto");
        profile.addProperty("skin", "LocalSkin/skins/{USERNAME}.png");
        profile.addProperty("cape", "LocalSkin/skins/{USERNAME}_cape.png");
        return profile;
    }

    private List<Skin> load() throws IOException {
        if (!Files.isRegularFile(indexFile)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            Index parsed = GSON.fromJson(reader, Index.class);
            List<Skin> clean = new ArrayList<>();
            if (parsed == null || parsed.skins == null) return clean;
            for (Stored stored : parsed.skins) {
                if (stored == null || stored.id == null || !ID.matcher(stored.id).matches()) continue;
                String name = stored.name == null || stored.name.isBlank() ? "skin" : cleanName(stored.name);
                String player = stored.player == null ? "" : stored.player;
                if (!player.isEmpty() && !PLAYER.matcher(player).matches()) continue;
                String model = "slim".equals(stored.model) ? "slim" : "classic";
                clean.add(new Skin(stored.id, name, player, model, Math.max(0L, stored.addedAt)));
            }
            return clean;
        } catch (Failure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Failure(Failure.Code.CONFIG_UNREADABLE, "");
        }
    }

    private void save(List<Skin> skins) throws IOException {
        Index index = new Index();
        for (Skin skin : skins) {
            Stored stored = new Stored();
            stored.id = skin.id;
            stored.name = skin.name;
            stored.player = skin.player;
            stored.model = skin.model;
            stored.addedAt = skin.addedAt;
            index.skins.add(stored);
        }
        Files.createDirectories(root);
        writeAtomic(indexFile, GSON.toJson(index) + "\n");
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = parent.resolve(target.getFileName().toString() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void validatePng(Path skinFile) throws IOException {
        String filename = skinFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".png")) throw new Failure(Failure.Code.NOT_PNG, "");
        long size = Files.size(skinFile);
        if (size < 33) throw new Failure(Failure.Code.TOO_SMALL, "");
        if (size > MAX_BYTES) throw new Failure(Failure.Code.TOO_LARGE, "");
        byte[] header;
        try (InputStream in = Files.newInputStream(skinFile)) {
            header = in.readNBytes(24);
        }
        if (header.length < 24) throw new Failure(Failure.Code.BAD_MAGIC, "");
        byte[] sig = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < sig.length; i++) {
            if (header[i] != sig[i]) throw new Failure(Failure.Code.BAD_MAGIC, "");
        }
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
        boolean ok = (width == 64 && (height == 32 || height == 64)) || (width == 128 && height == 128);
        if (!ok) throw new Failure(Failure.Code.BAD_SIZE, width + "x" + height);
    }

    private static String requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new Failure(Failure.Code.NOT_FOUND, "");
        return id;
    }

    private static String cleanName(String displayName) {
        String name = displayName == null ? "" : displayName.trim().replaceAll("[\\p{Cntrl}]", "");
        if (name.isEmpty()) return "skin";
        if (name.length() > 32) name = name.substring(0, 32).trim();
        if (name.isEmpty()) throw new Failure(Failure.Code.BAD_NAME, "");
        return name;
    }

    private static String cleanPlayer(String player) {
        String name = player == null ? "" : player.trim();
        if (name.isEmpty()) return "";
        if (!PLAYER.matcher(name).matches()) throw new Failure(Failure.Code.BAD_PLAYER, name);
        return name;
    }

    private static String cleanModel(String model) {
        if (model == null || model.isBlank() || "classic".equals(model)) return "classic";
        if ("slim".equals(model)) return "slim";
        throw new Failure(Failure.Code.BAD_MODEL, "");
    }

    private static Skin toSkin(Stored stored) {
        return new Skin(stored.id, stored.name, stored.player, stored.model, stored.addedAt);
    }

    public record Skin(String id, String name, String player, String model, long addedAt) {}

    public record DeployResult(int written, boolean profileAdded, boolean profileKept) {}

    private static final class Stored {
        String id;
        String name;
        String player;
        String model;
        long addedAt;
    }

    private static final class Index {
        List<Stored> skins = new ArrayList<>();
    }

    public static final class Failure extends RuntimeException {
        public enum Code {
            NOT_PNG, TOO_SMALL, TOO_LARGE, BAD_MAGIC, BAD_SIZE, BAD_NAME, BAD_PLAYER,
            DUPLICATE_PLAYER, NOT_FOUND, BAD_MODEL, NO_GAME_DIR, CONFIG_UNREADABLE
        }

        private final Code code;
        private final String detail;

        public Failure(Code code, String detail) {
            super(code.name());
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }

        public Code code() { return code; }

        public String detail() { return detail; }
    }
}
