using System.Collections.Concurrent;
using System.Globalization;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Pmcl.Ecxp;

/// <summary>
/// 应用和 PMCL 之间的连接。PMCL 启动应用时会带上 PMCL_ECXP_ENDPOINT 和 PMCL_ECXP_TOKEN，
/// <see cref="ConnectAsync(CancellationToken)"/> 读这两个变量连回启动器。
/// </summary>
/// <remarks>
/// 事件在后台线程上触发。改界面前先切回 UI 线程，例如 Avalonia 的 <c>Dispatcher.UIThread.Post</c>。
/// </remarks>
public sealed class PmclApp : IAsyncDisposable
{
    internal static readonly JsonSerializerOptions Json = CreateJson();

    private readonly TcpClient _tcp;
    private readonly NetworkStream _stream;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly ConcurrentDictionary<long, TaskCompletionSource<JsonElement>> _pending = new();
    private readonly ConcurrentDictionary<string, (Action<JsonElement[]> Handler, bool Once)> _callbacks = new();
    private readonly CancellationTokenSource _cts = new();
    private long _nextId;
    private Task? _reader;

    private PmclApp(TcpClient tcp)
    {
        _tcp = tcp;
        _stream = tcp.GetStream();
        Instances = new InstancesApi(this);
        Versions = new VersionsApi(this);
        Accounts = new AccountsApi(this);
        Launch = new LaunchApi(this);
        Games = new GameProcessApi(this);
        Mods = new ModsApi(this);
        Servers = new ServersApi(this);
        Settings = new SettingsApi(this);
        Ui = new UiApi(this);
        Files = new FilesystemApi(this);
    }

    /// <summary>PMCL 握手时返回的启动器和应用信息。</summary>
    public PmclHostInfo Info { get; private set; } = null!;

    public InstancesApi Instances { get; }
    public VersionsApi Versions { get; }
    public AccountsApi Accounts { get; }
    public LaunchApi Launch { get; }
    public GameProcessApi Games { get; }
    public ModsApi Mods { get; }
    public ServersApi Servers { get; }
    public SettingsApi Settings { get; }
    public UiApi Ui { get; }
    public FilesystemApi Files { get; }

    /// <summary>启动器里发生的事，例如游戏启动、退出、切换账号。</summary>
    public event Action<PmclEvent>? LauncherEvent;

    /// <summary>用户在 PMCL 里又点了一次这个应用，应把窗口提到前面。</summary>
    public event Action? ActivateRequested;

    /// <summary>PMCL 要关掉这个应用。几秒内没退出会被强制结束。</summary>
    public event Action? ShutdownRequested;

    /// <summary>连接断开，通常是启动器退出了。</summary>
    public event Action? Disconnected;

    /// <summary>是否由 PMCL 启动。直接双击运行时为 false。</summary>
    public static bool IsHosted =>
        !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("PMCL_ECXP_TOKEN"))
        && !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("PMCL_ECXP_ENDPOINT"));

    /// <summary>启动器当前是深色还是浅色界面："dark" 或 "light"。</summary>
    public static string Theme => Environment.GetEnvironmentVariable("PMCL_ECXP_THEME") ?? "dark";

    /// <summary>启动器的强调色，形如 #3D7EFF。没有时为 null。</summary>
    public static string? Accent => Environment.GetEnvironmentVariable("PMCL_ECXP_ACCENT");

    /// <summary>启动器界面语言，例如 zh-CN、en-US、ja。</summary>
    public static string Language =>
        Environment.GetEnvironmentVariable("PMCL_ECXP_LANG") ?? CultureInfo.CurrentUICulture.Name;

    /// <summary>应用自己的数据目录，重装时保留，卸载时删除。</summary>
    public static string? DataDirectory => Environment.GetEnvironmentVariable("PMCL_ECXP_DATA");

    /// <summary>按 PMCL 给的环境变量连回启动器。</summary>
    public static Task<PmclApp> ConnectAsync(CancellationToken cancellationToken = default)
    {
        var endpoint = Environment.GetEnvironmentVariable("PMCL_ECXP_ENDPOINT");
        var token = Environment.GetEnvironmentVariable("PMCL_ECXP_TOKEN");
        if (string.IsNullOrEmpty(endpoint) || string.IsNullOrEmpty(token))
            throw new PmclException("not_hosted", "This app was not started by PMCL.");
        return ConnectAsync(endpoint, token, cancellationToken);
    }

    public static async Task<PmclApp> ConnectAsync(string endpoint, string token, CancellationToken cancellationToken = default)
    {
        var split = endpoint.LastIndexOf(':');
        if (split <= 0 || !int.TryParse(endpoint[(split + 1)..], out var port))
            throw new PmclException("bad_endpoint", endpoint);
        var tcp = new TcpClient { NoDelay = true };
        try
        {
            await tcp.ConnectAsync(endpoint[..split], port, cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
        var app = new PmclApp(tcp);
        app._reader = Task.Run(app.ReadLoopAsync);
        try
        {
            var hello = await app.SendAsync("hello", new { token }, cancellationToken).ConfigureAwait(false);
            app.Info = hello.Deserialize<PmclHostInfo>(Json)!;
        }
        catch
        {
            await app.DisposeAsync().ConfigureAwait(false);
            throw;
        }
        return app;
    }

    /// <summary>
    /// 调用任意启动器接口。方法名是 “接口.方法”，例如 <c>instances.listInstances</c>，参数按顺序传。
    /// 可用的方法见 <see cref="ListMethodsAsync"/>。
    /// </summary>
    public Task<JsonElement> CallAsync(string method, params object?[] args) =>
        SendAsync(method, args, CancellationToken.None);

    public async Task<T> CallAsync<T>(string method, params object?[] args)
    {
        var result = await SendAsync(method, args, CancellationToken.None).ConfigureAwait(false);
        return result.Deserialize<T>(Json)!;
    }

    /// <summary>列出启动器当前提供的所有方法和参数类型。</summary>
    public Task<IReadOnlyList<PmclMethod>> ListMethodsAsync() => CallAsync<IReadOnlyList<PmclMethod>>("host.methods");

    /// <summary>写一行到 PMCL 的日志。</summary>
    public Task LogAsync(string message, string level = "info") => CallAsync("host.log", level, message);

    /// <summary>
    /// 包一个回调，作为参数传给需要回调的接口，例如 <see cref="UiApi.PickFolderAsync"/>。
    /// 启动器调用它时，<paramref name="handler"/> 收到回调的参数。
    /// </summary>
    public PmclCallback Callback(Action<JsonElement[]> handler, bool once = true)
    {
        var id = "cb" + Interlocked.Increment(ref _nextId).ToString(CultureInfo.InvariantCulture);
        _callbacks[id] = (handler, once);
        return new PmclCallback(id);
    }

    private async Task<JsonElement> SendAsync(string method, object? args, CancellationToken cancellationToken)
    {
        var id = Interlocked.Increment(ref _nextId);
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = tcs;
        var frame = JsonSerializer.SerializeToUtf8Bytes(new Request(id, method, args), Json);
        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await _stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            _stream.WriteByte((byte)'\n');
            await _stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            _pending.TryRemove(id, out _);
            throw new PmclException("disconnected", e.Message);
        }
        finally
        {
            _writeLock.Release();
        }
        using (cancellationToken.Register(() => tcs.TrySetCanceled(cancellationToken)))
        {
            return await tcs.Task.ConfigureAwait(false);
        }
    }

    private async Task ReadLoopAsync()
    {
        using var reader = new StreamReader(_stream, new UTF8Encoding(false), false, 64 * 1024, leaveOpen: true);
        try
        {
            while (!_cts.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(_cts.Token).ConfigureAwait(false);
                if (line == null) break;
                if (line.Length == 0) continue;
                Handle(line);
            }
        }
        catch (OperationCanceledException) { }
        catch (IOException) { }
        catch (ObjectDisposedException) { }
        finally
        {
            foreach (var pending in _pending.Values)
                pending.TrySetException(new PmclException("disconnected", "PMCL closed the connection."));
            _pending.Clear();
            Disconnected?.Invoke();
        }
    }

    private void Handle(string line)
    {
        JsonElement root;
        try
        {
            using var doc = JsonDocument.Parse(line);
            root = doc.RootElement.Clone();
        }
        catch (JsonException)
        {
            return;
        }

        if (root.TryGetProperty("event", out var ev) && ev.ValueKind == JsonValueKind.String)
        {
            var data = root.TryGetProperty("data", out var d) ? d : default;
            switch (ev.GetString())
            {
                case "activate":
                    ActivateRequested?.Invoke();
                    break;
                case "shutdown":
                    ShutdownRequested?.Invoke();
                    break;
                case "callback":
                    RunCallback(data);
                    break;
                case "pmcl":
                    var type = data.ValueKind == JsonValueKind.Object && data.TryGetProperty("type", out var t) ? t.GetString() ?? "" : "";
                    var payload = data.ValueKind == JsonValueKind.Object && data.TryGetProperty("data", out var p) ? p : default;
                    LauncherEvent?.Invoke(new PmclEvent(type, payload));
                    break;
            }
            return;
        }

        if (!root.TryGetProperty("id", out var idEl) || !idEl.TryGetInt64(out var id)) return;
        if (!_pending.TryRemove(id, out var tcs)) return;
        if (root.TryGetProperty("error", out var err) && err.ValueKind == JsonValueKind.Object)
        {
            var code = err.TryGetProperty("code", out var c) ? c.GetString() ?? "failed" : "failed";
            var message = err.TryGetProperty("message", out var m) ? m.GetString() ?? "" : "";
            tcs.TrySetException(new PmclException(code, message));
        }
        else
        {
            tcs.TrySetResult(root.TryGetProperty("result", out var r) ? r : default);
        }
    }

    private void RunCallback(JsonElement data)
    {
        if (data.ValueKind != JsonValueKind.Object || !data.TryGetProperty("id", out var idEl)) return;
        var id = idEl.GetString();
        if (id == null || !_callbacks.TryGetValue(id, out var entry)) return;
        if (entry.Once) _callbacks.TryRemove(id, out _);
        var args = data.TryGetProperty("args", out var a) && a.ValueKind == JsonValueKind.Array
            ? a.EnumerateArray().ToArray()
            : [];
        entry.Handler(args);
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        _tcp.Dispose();
        if (_reader != null)
        {
            try { await _reader.ConfigureAwait(false); } catch { }
        }
        _cts.Dispose();
        _writeLock.Dispose();
    }

    private static JsonSerializerOptions CreateJson()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }

    private sealed record Request(long Id, string Method, object? Params);
}

/// <summary>启动器返回的错误。Code 例如 permission_denied、unknown_method、failed。</summary>
public sealed class PmclException(string code, string message) : Exception(message.Length == 0 ? code : $"{code}: {message}")
{
    public string Code { get; } = code;
}

/// <summary>传给启动器的回调句柄。</summary>
public sealed record PmclCallback([property: JsonPropertyName("$callback")] string Id);

/// <summary>启动器事件。Type 是事件类名，例如 GameLaunchedEvent。</summary>
public sealed record PmclEvent(string Type, JsonElement Data);

public sealed record PmclMethod(string Name, IReadOnlyList<string> Params, string Returns);

public sealed record PmclAppInfo(string Id, string Name, string Version, string Framework);

public sealed record PmclHostInfo(
    int ApiVersion,
    string Launcher,
    string LauncherVersion,
    string WorkDir,
    string DataDir,
    PmclAppInfo App,
    IReadOnlyList<string> Permissions,
    IReadOnlyList<string> Apis,
    bool ApiAvailable);
