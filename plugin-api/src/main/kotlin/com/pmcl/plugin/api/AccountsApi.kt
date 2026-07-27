package com.pmcl.plugin.api

/**
 * Account identity API — never exposes access tokens.
 * Reads require [com.pmcl.plugin.PluginPermission.READ_ACCOUNTS].
 * Mutations require [com.pmcl.plugin.PluginPermission.WRITE_ACCOUNTS].
 */
interface AccountsApi {
    fun listAccounts(): List<AccountSummary>

    fun getSelectedAccount(): AccountSummary?

    /** Select an existing account by UUID. */
    fun selectAccount(uuid: String)

    /**
     * Create (or upsert) an offline account and select it.
     * @return account UUID
     */
    fun addOfflineAccount(username: String): String

    /** Remove an account by UUID. */
    fun removeAccount(uuid: String)
}
