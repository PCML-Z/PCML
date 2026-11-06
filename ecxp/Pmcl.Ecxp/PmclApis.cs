using System.Text.Json;

namespace Pmcl.Ecxp;

public sealed record InstanceSummary(
    string Id, string Name, string BaseVersionId, string? Loader, string? LoaderVersion, string Type, string? Description);

public sealed record VersionSummary(string Id, bool Launchable, string? InheritsFrom, long LastModified);

public sealed record RemoteVersionSummary(string Id, string Type, string ReleaseTime, string Url);

public sealed record AccountSummary(string Uuid, string Username, string Type, bool Selected);

public sealed record GameProcessSummary(string VersionId, long Pid, bool Running);

public sealed record ModSummary(string ModId, string Name, string Version, string Loader, string FileName, bool Disabled);

public sealed record ServerSummary(string Name, string Host, int Port);

public sealed record ServerPingResult(bool Online, long LatencyMs, string Motd, int OnlinePlayers, int MaxPlayers, string VersionName);

public enum NotificationLevel { INFO, WARN, ERROR, SUCCESS }

public enum DialogKind { INFO, CONFIRM }

/// <summary>每个接口共用的调用入口。</summary>
public abstract class PmclApiBase(PmclApp app, string name)
{
    protected PmclApp App { get; } = app;

    protected Task<JsonElement> Call(string method, params object?[] args) => App.CallAsync(name + "." + method, args);

    protected Task<T> Call<T>(string method, params object?[] args) => App.CallAsync<T>(name + "." + method, args);
}

public sealed class InstancesApi(PmclApp app) : PmclApiBase(app, "instances")
{
    public Task<IReadOnlyList<InstanceSummary>> ListAsync() => Call<IReadOnlyList<InstanceSummary>>("listInstances");
    public Task<InstanceSummary?> GetAsync(string id) => Call<InstanceSummary?>("getInstance", id);
    public Task<string> CreateAsync(string name, string baseVersionId, string? loader = null, string? loaderVersion = null) =>
        Call<string>("createInstance", name, baseVersionId, loader, loaderVersion);
    public Task RenameAsync(string id, string newName) => Call("renameInstance", id, newName);
    public Task DeleteAsync(string id) => Call("deleteInstance", id);
    public Task SetDescriptionAsync(string id, string? description) => Call("setDescription", id, description);
    public Task<string> DirectoryAsync(string id) => Call<string>("resolveInstanceDir", id);
}

public sealed class VersionsApi(PmclApp app) : PmclApiBase(app, "versions")
{
    public Task<IReadOnlyList<VersionSummary>> ListLocalAsync() => Call<IReadOnlyList<VersionSummary>>("listLocalVersions");
    public Task<IReadOnlyList<RemoteVersionSummary>> ListRemoteAsync(int limit = 100) =>
        Call<IReadOnlyList<RemoteVersionSummary>>("listRemoteVersions", limit);
    public Task<bool> IsLaunchableAsync(string versionId) => Call<bool>("isLaunchable", versionId);
    public Task<string> InstallAsync(string versionId) => Call<string>("installVersion", versionId);
    public Task DeleteAsync(string versionId) => Call("deleteVersion", versionId);
    public Task<string> DirectoryAsync(string versionId) => Call<string>("resolveVersionDir", versionId);
}

public sealed class AccountsApi(PmclApp app) : PmclApiBase(app, "accounts")
{
    public Task<IReadOnlyList<AccountSummary>> ListAsync() => Call<IReadOnlyList<AccountSummary>>("listAccounts");
    public Task<AccountSummary?> SelectedAsync() => Call<AccountSummary?>("getSelectedAccount");
    public Task SelectAsync(string uuid) => Call("selectAccount", uuid);
    public Task<string> AddOfflineAsync(string username) => Call<string>("addOfflineAccount", username);
}

public sealed class LaunchApi(PmclApp app) : PmclApiBase(app, "launch")
{
    /// <summary>启动一个版本。返回 null 表示已交给启动器，否则是失败原因。</summary>
    public Task<string?> VersionAsync(string versionId) => Call<string?>("requestLaunch", versionId);
    /// <summary>启动一个实例。返回 null 表示已交给启动器，否则是失败原因。</summary>
    public Task<string?> InstanceAsync(string instanceId) => Call<string?>("requestLaunchInstance", instanceId);
    public Task KillAllAsync() => Call("killAllGames");
    public Task<bool> IsGameRunningAsync() => Call<bool>("isGameRunning");
    public Task<int> ActiveCountAsync() => Call<int>("activeProcessCount");
}

public sealed class GameProcessApi(PmclApp app) : PmclApiBase(app, "gameProcess")
{
    public Task<IReadOnlyList<GameProcessSummary>> ListAsync() => Call<IReadOnlyList<GameProcessSummary>>("listProcesses");
    public Task<bool> KillAsync(long pid) => Call<bool>("killProcess", pid);
}

public sealed class ModsApi(PmclApp app) : PmclApiBase(app, "mods")
{
    public Task<string> DirectoryAsync(string? versionOrInstanceId) => Call<string>("resolveModsDir", versionOrInstanceId);
    public Task<IReadOnlyList<ModSummary>> ScanAsync(string modsDir) => Call<IReadOnlyList<ModSummary>>("scanMods", modsDir);
    public Task EnableAsync(string jarPath) => Call("enableMod", jarPath);
    public Task DisableAsync(string jarPath) => Call("disableMod", jarPath);
    public Task DeleteAsync(string jarPath) => Call("deleteMod", jarPath);
}

public sealed class ServersApi(PmclApp app) : PmclApiBase(app, "servers")
{
    public Task<IReadOnlyList<ServerSummary>> FavoritesAsync() => Call<IReadOnlyList<ServerSummary>>("listFavorites");
    public Task AddFavoriteAsync(string name, string host, int port = 25565) => Call("addFavorite", name, host, port);
    public Task RemoveFavoriteAsync(int index) => Call("removeFavorite", index);
    public Task<ServerPingResult> PingAsync(string host, int port = 25565) => Call<ServerPingResult>("ping", host, port);
}

public sealed class SettingsApi(PmclApp app) : PmclApiBase(app, "settings")
{
    public Task<string> LanguageAsync() => Call<string>("getLanguage");
    public Task<bool> IsDarkThemeAsync() => Call<bool>("isDarkTheme");
    public Task<int> MaxMemoryMbAsync() => Call<int>("getMaxMemoryMb");
    public Task SetMaxMemoryMbAsync(int mb) => Call("setMaxMemoryMb", mb);
    public Task<string> JavaPathAsync() => Call<string>("getJavaPath");
    public Task SetJavaPathAsync(string path) => Call("setJavaPath", path);
    public Task<string> JvmArgsAsync() => Call<string>("getCustomJvmArgs");
    public Task SetJvmArgsAsync(string args) => Call("setCustomJvmArgs", args);
}

public sealed class UiApi(PmclApp app) : PmclApiBase(app, "ui")
{
    public Task NotifyAsync(string title, string message, NotificationLevel level = NotificationLevel.INFO) =>
        Call("notify", title, message, level);

    /// <summary>让启动器主窗口跳到某个页面，例如 launch、settings、ecxp。</summary>
    public Task NavigateAsync(string target) => Call("navigate", target);
    public Task OpenUrlAsync(string url) => Call("openUrl", url);
    public Task CopyAsync(string text) => Call("copyToClipboard", text);
    public Task ProgressAsync(string id, string title, double progress) => Call("showProgress", id, title, progress);
    public Task DismissProgressAsync(string id) => Call("dismissProgress", id);

    /// <summary>在启动器里弹确认框，用户选完后返回 true / false。</summary>
    public Task<bool> ConfirmAsync(string title, string message, string confirm = "OK", string cancel = "Cancel")
    {
        var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var cb = App.Callback(args => done.TrySetResult(args.Length > 0 && args[0].ValueKind == JsonValueKind.True));
        return Call("showDialog", title, message, DialogKind.CONFIRM, confirm, cancel, cb).ContinueWith(t =>
        {
            if (t.IsFaulted) done.TrySetException(t.Exception!.InnerExceptions);
            return done.Task;
        }).Unwrap();
    }

    /// <summary>用启动器的文件夹选择框选一个目录。取消时返回 null。</summary>
    public Task<string?> PickFolderAsync(string title) => PathResult(cb => Call("pickFolder", title, cb));

    /// <summary>用启动器的文件选择框。filters 写成 “jar;zip”。取消时返回 null。</summary>
    public Task<string?> PickFileAsync(string title, string filters = "", bool save = false) =>
        PathResult(cb => Call("pickFile", title, filters, save, cb));

    private Task<string?> PathResult(Func<PmclCallback, Task> send)
    {
        var done = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var cb = App.Callback(args => done.TrySetResult(args.Length > 0 && args[0].ValueKind == JsonValueKind.String ? args[0].GetString() : null));
        return send(cb).ContinueWith(t =>
        {
            if (t.IsFaulted) done.TrySetException(t.Exception!.InnerExceptions);
            return done.Task;
        }).Unwrap();
    }
}

public sealed class FilesystemApi(PmclApp app) : PmclApiBase(app, "filesystem")
{
    public Task<string> WorkDirAsync() => Call<string>("workDir");
    public Task<string> DataDirAsync() => Call<string>("dataDir");
    public Task<bool> ExistsAsync(string path) => Call<bool>("exists", path);
    public Task<IReadOnlyList<string>> ListAsync(string path) => Call<IReadOnlyList<string>>("list", path);
    public Task<string> ReadTextAsync(string path) => Call<string>("readText", path);
    public Task WriteTextAsync(string path, string content) => Call("writeText", path, content);
}
