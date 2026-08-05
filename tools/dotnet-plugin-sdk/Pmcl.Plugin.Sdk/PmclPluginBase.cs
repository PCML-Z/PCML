namespace Pmcl.Plugin.Sdk;

using System.Runtime.CompilerServices;
using System.Text.Json;

/// <summary>
/// PMCL 传给外部运行时插件的命令行参数。
/// 由 <see cref="Parse"/> 在 Main 最开始解析一次。
/// </summary>
public static class PmclArgs
{
    /// <summary>是否运行在 PMCL 插件模式（<c>--pmcl-ipc=jsonrpc-stdin</c>）。</summary>
    public static bool IpcMode { get; private set; }

    /// <summary>宿主分配的插件 id（<c>--pmcl-plugin-id=</c>）。</summary>
    public static string PluginId { get; private set; } = "";

    /// <summary>
    /// 嵌入模式下宿主分配的本地 HTTP 端口（<c>--pmcl-web-port=</c>）；
    /// 未传则为 -1，表示不需要提供 Web UI。
    /// </summary>
    public static int WebPort { get; private set; } = -1;

    public static void Parse(string[] args)
    {
        foreach (var a in args)
        {
            if (a == "--pmcl-ipc=jsonrpc-stdin") IpcMode = true;
            else if (a.StartsWith("--pmcl-plugin-id=", StringComparison.Ordinal))
                PluginId = a["--pmcl-plugin-id=".Length..];
            else if (a.StartsWith("--pmcl-web-port=", StringComparison.Ordinal)
                     && int.TryParse(a["--pmcl-web-port=".Length..], out var p))
                WebPort = p;
        }
    }
}

/// <summary>
/// Base class for PMCL .NET plugins.
/// Plugins communicate with PMCL via stdin/stdout JSON-RPC 2.0.
/// </summary>
public abstract class PmclPluginBase
{
    private JsonRpcClient? _client;

    /// <summary>
    /// 宿主发来 <c>system.ready</c> 时调用。
    /// 嵌入模式下应在此**完成 HTTP 端口监听**——本方法返回后宿主才会去探测端口。
    /// </summary>
    public virtual Task OnEnable(PluginContext ctx) => Task.CompletedTask;

    /// <summary>插件被停用或 PMCL 退出时调用。</summary>
    public virtual Task OnDisable() => Task.CompletedTask;

    /// <summary>
    /// 处理宿主发来的自定义请求（非 <c>system.*</c>）。
    /// 返回值会作为 JSON-RPC result 回给宿主；返回 null 表示 <c>{ok:true}</c>。
    /// </summary>
    public virtual Task<object?> HandleRequestAsync(string method, JsonElement @params)
        => Task.FromResult<object?>(null);

    /// <summary>Log helper — writes to stderr (captured by PMCL ProcessBridge).</summary>
    protected static void Log(string msg, [CallerMemberName] string? member = null)
    {
        Console.Error.WriteLine($"[{member}] {msg}");
    }

    /// <summary>启动 stdin/stdout JSON-RPC 循环，直到宿主要求关闭为止。</summary>
    public async Task RunAsync()
    {
        _client = new JsonRpcClient(Console.OpenStandardInput(), Console.OpenStandardOutput());
        try
        {
            await _client.RunAsync(this).ConfigureAwait(false);
        }
        finally
        {
            _client.Dispose();
        }
    }
}

/// <summary>
/// Context passed to <see cref="PmclPluginBase.OnEnable"/>.
/// Provides typed access to PMCL APIs via JSON-RPC.
/// </summary>
public class PluginContext
{
    public PluginInfo Info { get; init; } = null!;
    public IJsonRpcClient Rpc { get; init; } = null!;

    public VersionsApi Versions => new(Rpc);
    public AccountsApi Accounts => new(Rpc);
    public LaunchApi Launch => new(Rpc);
    public ModsApi Mods => new(Rpc);
    public GameProcessApi GameProcess => new(Rpc);
    public LoaderVersionsApi LoaderVersions => new(Rpc);
    public NewsApi News => new(Rpc);
    public StatsApi Stats => new(Rpc);
    public MusicApi Music => new(Rpc);
    public UiApi Ui => new(Rpc);
    public SettingsApi Settings => new(Rpc);
    public InstancesApi Instances => new(Rpc);
}

public record PluginInfo(string Id, string Name, string Version, string Author);
