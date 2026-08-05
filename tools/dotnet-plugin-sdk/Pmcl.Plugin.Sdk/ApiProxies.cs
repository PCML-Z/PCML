namespace Pmcl.Plugin.Sdk;

using System.Text.Json;

// ==================== Typed API Proxies ====================

/// <summary>Minecraft version management.</summary>
public class VersionsApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> ListLocalAsync() => rpc.CallAsync("versions.listLocalVersions");
    public Task<JsonElement> ListRemoteAsync(int limit = 100) => rpc.CallAsync("versions.listRemoteVersions", new { limit });
    public Task<JsonElement> InstallAsync(string versionId) => rpc.CallAsync("versions.installVersion", new { versionId });
    public Task DeleteAsync(string versionId) => rpc.CallAsync("versions.deleteVersion", new { versionId });
}

/// <summary>Account management.</summary>
public class AccountsApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> ListAsync() => rpc.CallAsync("accounts.listAccounts");
    public Task SelectAsync(string uuid) => rpc.CallAsync("accounts.selectAccount", new { uuid });
    public Task<JsonElement> AddOfflineAsync(string username) => rpc.CallAsync("accounts.addOfflineAccount", new { username });
    public Task RemoveAsync(string uuid) => rpc.CallAsync("accounts.removeAccount", new { uuid });
}

/// <summary>Game launch control.</summary>
public class LaunchApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> IsGameRunningAsync() => rpc.CallAsync("launch.isGameRunning");
    public Task<JsonElement> ActiveProcessCountAsync() => rpc.CallAsync("launch.activeProcessCount");
    public Task KillAllAsync() => rpc.CallAsync("launch.killAllGames");

    /// <summary>
    /// 请求 PMCL 用当前选中账号启动指定版本。
    /// 返回 <c>{ accepted: bool, reason: string|null }</c>。
    /// </summary>
    public Task<JsonElement> RequestLaunchAsync(string versionId)
        => rpc.CallAsync("launch.requestLaunch", new { versionId });

    /// <summary>请求 PMCL 启动某个实例。返回结构同 <see cref="RequestLaunchAsync"/>。</summary>
    public Task<JsonElement> RequestLaunchInstanceAsync(string instanceId)
        => rpc.CallAsync("launch.requestLaunchInstance", new { instanceId });
}

/// <summary>Mod management.</summary>
public class ModsApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> ScanAsync(string? modsDir = null) => rpc.CallAsync("mods.scanMods", new { modsDir });
    public Task EnableAsync(string jarPath) => rpc.CallAsync("mods.enableMod", new { jarPath });
    public Task DisableAsync(string jarPath) => rpc.CallAsync("mods.disableMod", new { jarPath });
}

/// <summary>Game process monitoring.</summary>
public class GameProcessApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> ListAsync() => rpc.CallAsync("gameProcess.listProcesses");
    public Task<JsonElement> KillAsync(long pid) => rpc.CallAsync("gameProcess.killProcess", new { pid });
}

/// <summary>Mod loader version queries.</summary>
public class LoaderVersionsApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> ListVersionsAsync(string loader, string gameVersion)
        => rpc.CallAsync("loaderVersions.listVersions", new { loader, gameVersion });
}

/// <summary>Minecraft news.</summary>
public class NewsApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> FetchAsync(int limit = 20) => rpc.CallAsync("news.fetchNews", new { limit });
}

/// <summary>Statistics.</summary>
public class StatsApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> OverallAsync(int recentDays = 30) => rpc.CallAsync("stats.overall", new { recentDays });
    public Task<JsonElement> SessionsAsync(int offset = 0, int limit = 50) => rpc.CallAsync("stats.sessions", new { offset, limit });
}

/// <summary>Music player control.</summary>
public class MusicApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> NowPlayingAsync() => rpc.CallAsync("music.nowPlaying");
    public Task PauseAsync() => rpc.CallAsync("music.pause");
    public Task ResumeAsync() => rpc.CallAsync("music.resume");
    public Task NextAsync() => rpc.CallAsync("music.playNext");
}

/// <summary>UI operations (notifications, navigation).</summary>
public class UiApi(IJsonRpcClient rpc)
{
    public Task NotifyAsync(string message) => rpc.CallAsync("ui.notify", new { message });
    public Task NavigateAsync(string target) => rpc.CallAsync("ui.navigate", new { target });
    public Task CopyToClipboardAsync(string text) => rpc.CallAsync("ui.copyToClipboard", new { text });
}

/// <summary>Settings access.</summary>
public class SettingsApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> GetLanguageAsync() => rpc.CallAsync("settings.getLanguage");
    public Task<JsonElement> GetMaxMemoryAsync() => rpc.CallAsync("settings.getMaxMemoryMb");
}

/// <summary>Instance management.</summary>
public class InstancesApi(IJsonRpcClient rpc)
{
    public Task<JsonElement> ListAsync() => rpc.CallAsync("instances.listInstances");
    public Task<JsonElement> CreateAsync(string name, string baseVersionId, string? loader = null, string? loaderVersion = null)
        => rpc.CallAsync("instances.createInstance", new { name, baseVersionId, loader, loaderVersion });
}
