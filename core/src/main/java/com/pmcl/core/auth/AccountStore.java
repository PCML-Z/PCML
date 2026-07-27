package com.pmcl.core.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 多账号存储模型。
 * <p>
 * P2-1: 增加 corruptedAccounts 字段，记录因 keyfile 丢失/损坏而无法解密的账号用户名，
 * 供 UI 层提示用户"检测到 N 个账号无法解密，可能因机器标识变化，请重新登录"。
 */
public final class AccountStore {

    private final List<Account> accounts;
    private final String selectedUuid;
    private final List<String> corruptedAccounts;

    public AccountStore(List<Account> accounts, String selectedUuid) {
        this(accounts, selectedUuid, new ArrayList<>());
    }

    public AccountStore(List<Account> accounts, String selectedUuid, List<String> corruptedAccounts) {
        this.accounts = new ArrayList<>(accounts);
        this.selectedUuid = selectedUuid;
        this.corruptedAccounts = new ArrayList<>(corruptedAccounts);
    }

    public List<Account> getAccounts() {
        return Collections.unmodifiableList(accounts);
    }

    public String getSelectedUuid() {
        return selectedUuid;
    }

    /** P2-1: 返回因解密失败而无法加载的账号用户名列表（供 UI 提示用户重新登录） */
    public List<String> getCorruptedAccounts() {
        return Collections.unmodifiableList(corruptedAccounts);
    }

    /** P2-1: 是否有账号因解密失败而丢失 */
    public boolean hasCorruptedAccounts() {
        return !corruptedAccounts.isEmpty();
    }

    public Optional<Account> getSelected() {
        if (selectedUuid == null) return Optional.empty();
        return accounts.stream().filter(a -> a.getUuid().equals(selectedUuid)).findFirst();
    }

    /** 返回新的 AccountStore：添加（已存在则替换），不修改原对象 */
    public AccountStore upsert(Account account) {
        List<Account> newList = new ArrayList<>();
        for (Account a : accounts) {
            if (!a.getUuid().equals(account.getUuid())) newList.add(a);
        }
        newList.add(account);
        return new AccountStore(newList, account.getUuid(), corruptedAccounts);
    }

    /** 返回新的 AccountStore：删除指定 uuid */
    public AccountStore remove(String uuid) {
        List<Account> newList = new ArrayList<>();
        for (Account a : accounts) {
            if (!a.getUuid().equals(uuid)) newList.add(a);
        }
        String newSelected = uuid.equals(selectedUuid)
                ? (newList.isEmpty() ? null : newList.get(0).getUuid())
                : selectedUuid;
        return new AccountStore(newList, newSelected, corruptedAccounts);
    }

    /** 返回新的 AccountStore：切换选中账号（不改变账号列表） */
    public AccountStore select(String uuid) {
        return new AccountStore(accounts, uuid, corruptedAccounts);
    }
}
