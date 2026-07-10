package com.pmcl.core.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 账号认证 + 多账号管理。
 * <p>
 * 多账号存储格式（~/.pmcl/accounts.json）：
 * <pre>
 * {
 *   "selected": "uuid-of-current",
 *   "accounts": [
 *     { "uuid": "...", "username": "...", "accessToken": "...", "type": "OFFLINE|MICROSOFT" }
 *   ]
 * }
 * </pre>
 */
public final class AuthService {

    private final MicrosoftAuthFlow flow = new MicrosoftAuthFlow();
    private final Gson gson = new Gson();

    /**
     * 创建离线账号。
     */
    public Account offline(String username) {
        String uuid = UUID.nameUUIDFromBytes(
                ("Offline:" + username).getBytes()).toString();
        return new Account(username, uuid, uuid, Account.AccountType.OFFLINE);
    }

    /**
     * 请求设备码（UI 层显示给用户）。
     */
    public DeviceCode requestDeviceCode() throws IOException {
        return flow.requestDeviceCode();
    }

    /**
     * 异步等待用户完成登录，并完成剩余流程，最终返回 Account。
     */
    public CompletableFuture<Account> loginMicrosoftAsync(DeviceCode dc,
                                                          Consumer<String> onPending) {
        return flow.pollForMsAccessToken(dc, onPending)
                .thenApplyAsync(token -> {
                    try {
                        return flow.completeLogin(token);
                    } catch (IOException e) {
                        throw new RuntimeException("微软登录失败", e);
                    }
                });
    }

    // ============ 多账号持久化 ============

    /**
     * 加载所有账号 + 当前选中账号。
     * 文件不存在时返回空 AccountStore。
     */
    public AccountStore loadStore(Path file) throws IOException {
        if (!Files.exists(file)) return new AccountStore(new ArrayList<>(), null);
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Throwable t) {
            return new AccountStore(new ArrayList<>(), null);
        }
        List<Account> accounts = new ArrayList<>();
        if (root.has("accounts")) {
            for (JsonElement e : root.getAsJsonArray("accounts")) {
                JsonObject o = e.getAsJsonObject();
                Account.AccountType accountType;
                try {
                    accountType = Account.AccountType.valueOf(
                            o.has("type") && !o.get("type").isJsonNull() ? o.get("type").getAsString() : "OFFLINE");
                } catch (IllegalArgumentException ex) {
                    accountType = Account.AccountType.OFFLINE;
                }
                accounts.add(new Account(
                        o.has("username") && !o.get("username").isJsonNull() ? o.get("username").getAsString() : "",
                        o.has("uuid") && !o.get("uuid").isJsonNull() ? o.get("uuid").getAsString() : "",
                        o.has("accessToken") && !o.get("accessToken").isJsonNull() ? o.get("accessToken").getAsString() : "",
                        accountType,
                        o.has("skinUrl") && !o.get("skinUrl").isJsonNull() ? o.get("skinUrl").getAsString() : "",
                        o.has("skinModel") && !o.get("skinModel").isJsonNull() ? o.get("skinModel").getAsString() : "classic"
                ));
            }
        }
        String selected = root.has("selected") ? root.get("selected").getAsString() : null;
        return new AccountStore(accounts, selected);
    }

    /**
     * 保存账号集合 + 选中状态。
     */
    public void saveStore(AccountStore store, Path file) throws IOException {
        JsonObject root = new JsonObject();
        if (store.getSelectedUuid() != null) {
            root.addProperty("selected", store.getSelectedUuid());
        }
        JsonArray arr = new JsonArray();
        for (Account a : store.getAccounts()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", a.getUuid());
            o.addProperty("username", a.getUsername());
            o.addProperty("accessToken", a.getAccessToken());
            o.addProperty("type", a.getType().name());
            o.addProperty("skinUrl", a.getSkinUrl());
            o.addProperty("skinModel", a.getSkinModel());
            arr.add(o);
        }
        root.add("accounts", arr);
        Files.createDirectories(file.getParent());
        Files.writeString(file, gson.toJson(root));
    }

    // ============ 兼容旧 API（单账号文件） ============

    public void saveAccount(Account account, Path file) throws IOException {
        List<Account> list = new ArrayList<>();
        list.add(account);
        AccountStore store = new AccountStore(list, account.getUuid());
        saveStore(store, file);
    }

    public Account loadAccount(Path file) throws IOException {
        AccountStore store = loadStore(file);
        if (store.getSelectedUuid() == null) {
            return store.getAccounts().isEmpty() ? null : store.getAccounts().get(0);
        }
        Optional<Account> sel = store.getAccounts().stream()
                .filter(a -> a.getUuid().equals(store.getSelectedUuid()))
                .findFirst();
        return sel.orElse(null);
    }
}
