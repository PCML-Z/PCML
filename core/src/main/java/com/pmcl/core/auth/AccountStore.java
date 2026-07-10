package com.pmcl.core.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 多账号存储模型。
 */
public final class AccountStore {

    private final List<Account> accounts;
    private final String selectedUuid;

    public AccountStore(List<Account> accounts, String selectedUuid) {
        this.accounts = new ArrayList<>(accounts);
        this.selectedUuid = selectedUuid;
    }

    public List<Account> getAccounts() {
        return Collections.unmodifiableList(accounts);
    }

    public String getSelectedUuid() {
        return selectedUuid;
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
        return new AccountStore(newList, account.getUuid());
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
        return new AccountStore(newList, newSelected);
    }
}
