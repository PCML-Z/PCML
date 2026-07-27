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

    /**
     * 解析资产索引；任一 object 缺少 hash 则失败（避免静默跳过导致「安装成功但资源不全」）。
     */
    public static AssetIndex parse(String json) throws IOException {
        if (json == null || json.isBlank()) {
            throw new IOException("资产索引为空");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("资产索引 JSON 解析失败", e);
        }
        String id = root.has("name") && !root.get("name").isJsonNull()
                ? root.get("name").getAsString() : "";
        AssetIndex idx = new AssetIndex(id);
        if (!root.has("objects") || !root.get("objects").isJsonObject()) {
            throw new IOException("资产索引缺少 objects");
        }
        int missingHash = 0;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("objects").entrySet()) {
            if (!e.getValue().isJsonObject()) {
                missingHash++;
                continue;
            }
            JsonObject o = e.getValue().getAsJsonObject();
            if (!o.has("hash") || o.get("hash").isJsonNull()) {
                missingHash++;
                continue;
            }
            String hash = o.get("hash").getAsString();
            if (hash == null || !hash.matches("[0-9a-fA-F]{40}")) {
                missingHash++;
                continue;
            }
            long size = o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : 0;
            idx.assets.put(e.getKey(), new Asset(hash, size));
        }
        if (missingHash > 0) {
            throw new IOException("资产索引有 " + missingHash + " 个条目缺少有效 SHA-1，拒绝安装");
        }
        if (idx.assets.isEmpty()) {
            throw new IOException("资产索引 objects 为空");
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
