namespace Pmcl.Plugin.Sdk;

using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;

/// <summary>JSON-RPC 2.0 client over stdin/stdout.</summary>
public interface IJsonRpcClient
{
    Task<JsonElement> CallAsync(string method, object? args = null);
    void Notify(string method, object? args = null);
}

/// <summary>
/// 与 PMCL 宿主之间的 JSON-RPC 2.0 **双向** 通道（stdin/stdout，一行一帧）。
///
/// <para>协议要点（必须与 JVM 侧 ProcessBridge / PluginApiJsonRpcServer 对齐）：</para>
/// <list type="bullet">
///   <item>宿主把 <c>system.ready</c> 作为**请求**发过来（带 id），插件必须**回响应**，
///         宿主收到响应才认为启动成功；只发通知会让宿主 30 秒握手超时。</item>
///   <item>宿主停用插件时发 <c>system.shutdown</c> 请求，插件回响应后退出。</item>
///   <item>带 <c>method</c> 的帧一律先按「请求/通知」处理；只有不带 method、带 id 的
///         才是对本端 <see cref="CallAsync"/> 的响应。</item>
/// </list>
/// </summary>
public class JsonRpcClient : IJsonRpcClient, IDisposable
{
    private static readonly JsonSerializerOptions JsonOpts =
        new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    private readonly Stream _stdinStream;
    private readonly StreamWriter _stdout;
    private readonly ConcurrentDictionary<long, TaskCompletionSource<JsonElement>> _pending = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly CancellationTokenSource _cts = new();
    private readonly TaskCompletionSource _shutdownSignal =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    private long _nextId;
    private PmclPluginBase? _plugin;
    private PluginContext? _ctx;
    private int _enableGuard;   // 保证 OnEnable 只执行一次

    public event Action<string, JsonElement>? OnNotification;

    internal JsonRpcClient(Stream stdin, Stream stdout)
    {
        _stdinStream = stdin;
        _stdout = new StreamWriter(stdout, new UTF8Encoding(false)) { AutoFlush = true };
    }

    /// <summary>跑到 PMCL 要求关闭（或 stdin 断开）为止。</summary>
    internal async Task RunAsync(PmclPluginBase plugin)
    {
        _plugin = plugin;

        var pluginId = Environment.GetEnvironmentVariable("PMCL_PLUGIN_ID")
                       ?? PmclArgs.PluginId;
        if (string.IsNullOrEmpty(pluginId)) pluginId = "unknown";

        _ctx = new PluginContext
        {
            Info = new PluginInfo(pluginId, pluginId, "1.0", ".NET"),
            Rpc = this
        };

        var readTask = ReadLoopAsync();

        // 关键：必须阻塞在这里保持进程存活。
        // 旧实现在 OnEnable 之后直接返回，Main 随即结束、进程退出，
        // 宿主看到的就是「刚启动就死掉」。
        await Task.WhenAny(readTask, _shutdownSignal.Task).ConfigureAwait(false);

        try
        {
            await plugin.OnDisable().ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[Plugin] OnDisable failed: {ex.Message}");
        }

        _cts.Cancel();
    }

    private async Task ReadLoopAsync()
    {
        using var reader = new StreamReader(_stdinStream, Encoding.UTF8);
        try
        {
            while (!_cts.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(_cts.Token).ConfigureAwait(false);
                if (line == null) break;                     // stdin 关闭 → 宿主已退出
                var trimmed = line.Trim();
                if (trimmed.Length == 0) continue;

                try
                {
                    using var doc = JsonDocument.Parse(trimmed);
                    var root = doc.RootElement;

                    var hasMethod = root.TryGetProperty("method", out var methodEl)
                                    && methodEl.ValueKind == JsonValueKind.String;
                    var hasId = root.TryGetProperty("id", out var idEl)
                                && idEl.ValueKind == JsonValueKind.Number;

                    if (hasMethod)
                    {
                        var method = methodEl.GetString()!;
                        var prms = root.TryGetProperty("params", out var p)
                            ? p.Clone()
                            : default;

                        if (hasId)
                        {
                            // 宿主发来的请求 —— 必须回响应
                            var id = idEl.GetInt64();
                            _ = HandleRequestAsync(id, method, prms);
                        }
                        else
                        {
                            OnNotification?.Invoke(method, prms);
                        }
                    }
                    else if (hasId)
                    {
                        // 对本端 CallAsync 的响应
                        var id = idEl.GetInt64();
                        if (_pending.TryRemove(id, out var tcs))
                        {
                            if (root.TryGetProperty("error", out var err))
                            {
                                var msg = err.TryGetProperty("message", out var m)
                                    ? m.GetString() ?? "Unknown error"
                                    : "Unknown error";
                                tcs.TrySetException(new RpcException(msg));
                            }
                            else if (root.TryGetProperty("result", out var res))
                            {
                                tcs.TrySetResult(res.Clone());
                            }
                            else
                            {
                                tcs.TrySetResult(default);
                            }
                        }
                    }
                }
                catch (JsonException)
                {
                    // 跳过坏帧
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (IOException) { }
        finally
        {
            _shutdownSignal.TrySetResult();
        }
    }

    private async Task HandleRequestAsync(long id, string method, JsonElement prms)
    {
        try
        {
            switch (method)
            {
                case "system.ready":
                {
                    // 宿主等这个响应才算启动成功；OnEnable 里应完成端口监听等准备工作，
                    // 这样宿主返回后探测端口必定已就绪。
                    if (Interlocked.Exchange(ref _enableGuard, 1) == 0
                        && _plugin != null && _ctx != null)
                    {
                        await _plugin.OnEnable(_ctx).ConfigureAwait(false);
                    }
                    await SendResponseAsync(id, new
                    {
                        ok = true,
                        runtime = "dotnet",
                        webPort = PmclArgs.WebPort
                    }).ConfigureAwait(false);
                    return;
                }
                case "system.shutdown":
                {
                    await SendResponseAsync(id, new { ok = true }).ConfigureAwait(false);
                    _shutdownSignal.TrySetResult();
                    return;
                }
                case "system.ping":
                {
                    await SendResponseAsync(id, new { pong = true }).ConfigureAwait(false);
                    return;
                }
                default:
                {
                    object? result = _plugin == null
                        ? null
                        : await _plugin.HandleRequestAsync(method, prms).ConfigureAwait(false);
                    await SendResponseAsync(id, result ?? new { ok = true }).ConfigureAwait(false);
                    return;
                }
            }
        }
        catch (Exception ex)
        {
            await SendErrorAsync(id, -32603, $"{ex.GetType().Name}: {ex.Message}").ConfigureAwait(false);
        }
    }

    public async Task<JsonElement> CallAsync(string method, object? @params = null)
    {
        var id = Interlocked.Increment(ref _nextId);
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = tcs;

        await WriteFrameAsync(new { jsonrpc = "2.0", id, method, @params }).ConfigureAwait(false);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
        cts.CancelAfter(30_000);
        try
        {
            return await tcs.Task.WaitAsync(cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            _pending.TryRemove(id, out _);
            throw new TimeoutException($"RPC timeout: {method}");
        }
    }

    public void Notify(string method, object? @params = null)
    {
        _ = WriteFrameAsync(new { jsonrpc = "2.0", method, @params });
    }

    private Task SendResponseAsync(long id, object? result)
        => WriteFrameAsync(new { jsonrpc = "2.0", id, result });

    private Task SendErrorAsync(long id, int code, string message)
        => WriteFrameAsync(new { jsonrpc = "2.0", id, error = new { code, message } });

    /// <summary>串行写帧——多个并发的 API 调用/响应不能交错破坏行边界。</summary>
    private async Task WriteFrameAsync(object frame)
    {
        var json = JsonSerializer.Serialize(frame, JsonOpts);
        await _writeLock.WaitAsync().ConfigureAwait(false);
        try
        {
            await _stdout.WriteLineAsync(json).ConfigureAwait(false);
        }
        catch (IOException)
        {
            _shutdownSignal.TrySetResult();
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _shutdownSignal.TrySetResult();
        try { _stdout.Dispose(); } catch { }
        try { _stdinStream.Dispose(); } catch { }
        _writeLock.Dispose();
        _cts.Dispose();
        GC.SuppressFinalize(this);
    }
}

public class RpcException : Exception
{
    public RpcException(string message) : base(message) { }
}
