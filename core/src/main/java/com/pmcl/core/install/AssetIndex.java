package com.pmcl.core.install;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资产索引：包含所有资源文件（音效、贴图、语言等）的下载信息。
 */
public final class AssetIndex {

    private final String id;
    private final Map<String, Asset> assets = new LinkedHashMap<>();

    private AssetIndex(String id) {
        this.id = id;
    }

    public static AssetIndex parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String id = root.has("name") ? root.get("name").getAsString() : "";
        AssetIndex idx = new AssetIndex(id);
        if (root.has("objects")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("objects").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                if (!o.has("hash") || o.get("hash").isJsonNull()) continue;
                String hash = o.get("hash").getAsString();
                long size = o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : 0;
                idx.assets.put(e.getKey(), new Asset(hash, size));
            }
        }
        return idx;
    }

    public String getId() { return id; }
    public Map<String, Asset> getAssets() { return assets; }

    public static final class Asset {
        private final String hash;
        private final long size;

        public Asset(String hash, long size) {
            this.hash = hash;
            this.size = size;
        }

        public String getHash() { return hash; }
        public long getSize() { return size; }

        /** 资产存放路径：前两位 hash / hash */
        public String getPath() {
            if (hash == null || hash.length() < 2) return hash;
            return hash.substring(0, 2) + "/" + hash;
        }
    }
}
