package com.lash.pmcl.core.auth

/**
 * 多账号存储模型 — Android 版。
 *
 * 不可变值对象，所有变更操作返回新实例。
 *
 * corruptedAccounts 记录因 keyfile 丢失/损坏而无法解密的账号用户名，
 * 供 UI 层提示用户"检测到 N 个账号无法解密，可能因机器标识变化，请重新登录"。
 */
data class AccountStore(
    val accounts: List<Account>,
    val selectedUuid: String?,
    val corruptedAccounts: List<String> = emptyList()
) {

    constructor(accounts: List<Account>, selectedUuid: String?) :
        this(accounts, selectedUuid, emptyList())

    fun hasCorruptedAccounts(): Boolean = corruptedAccounts.isNotEmpty()

    fun getSelected(): Account? {
        if (selectedUuid == null) return null
        return accounts.firstOrNull { it.uuid == selectedUuid }
    }

    /** 添加（已存在则替换），不修改原对象 */
    fun upsert(account: Account): AccountStore {
        val newList = accounts.filter { it.uuid != account.uuid } + account
        return copy(accounts = newList, selectedUuid = account.uuid)
    }

    /** 删除指定 uuid */
    fun remove(uuid: String): AccountStore {
        val newList = accounts.filter { it.uuid != uuid }
        val newSelected = if (uuid == selectedUuid) {
            newList.firstOrNull()?.uuid
        } else selectedUuid
        return copy(accounts = newList, selectedUuid = newSelected)
    }

    /** 切换选中账号（不改变账号列表） */
    fun select(uuid: String): AccountStore = copy(selectedUuid = uuid)
}
