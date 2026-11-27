using System.Text.Json;
using System.Text.RegularExpressions;

namespace Pmcl.Installer;

/// <summary>
/// 从 GitHub Release 里挑当前系统的安装包。官方地址严格白名单；镜像只用于加速，
/// 且必须原样指向同一个官方 release 路径，下载完再用官方 API 给的 sha256 校验。
/// </summary>
public static class ReleaseChooser
{
    public const string LatestUrl = "https://api.github.com/repos/PCML-Z/PCML/releases/latest";
    public const long MaxBytes = 2L * 1024 * 1024 * 1024;

    private const string RepoPrefix = "/PCML-Z/PCML/releases/download/";
    private static readonly string[] MirrorHosts = ["gh-proxy.com", "ghfast.top"];

    private static readonly Regex Name = new(
        "^pmcl-[A-Za-z0-9._+-]+-(macos|windows|linux)-(arm64|x64)\\.(pkg|msi|deb|rpm)$",
        RegexOptions.CultureInvariant | RegexOptions.Compiled);

    public static bool IsAllowed(Uri uri)
    {
        if (!string.Equals(uri.Scheme, "https", StringComparison.OrdinalIgnoreCase)) return false;
        var host = uri.IdnHost;
        return host.Equals("api.github.com", StringComparison.OrdinalIgnoreCase)
            || host.Equals("github.com", StringComparison.OrdinalIgnoreCase)
            || host.Equals("objects.githubusercontent.com", StringComparison.OrdinalIgnoreCase)
            || host.Equals("release-assets.githubusercontent.com", StringComparison.OrdinalIgnoreCase)
            || host.Equals("github-releases.githubusercontent.com", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>镜像链接必须是 https://白名单主机/https://github.com/PCML-Z/PCML/releases/download/…。</summary>
    public static bool IsMirrorAllowed(Uri uri)
    {
        if (!string.Equals(uri.Scheme, "https", StringComparison.OrdinalIgnoreCase)) return false;
        if (!MirrorHosts.Any(h => uri.IdnHost.Equals(h, StringComparison.OrdinalIgnoreCase))) return false;
        return uri.AbsolutePath.StartsWith("/https://github.com" + RepoPrefix, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>为官方下载地址生成镜像链接；路径不匹配就不生成。</summary>
    public static IReadOnlyList<Uri> MirrorUrls(Uri official)
    {
        if (!official.IdnHost.Equals("github.com", StringComparison.OrdinalIgnoreCase)) return [];
        var path = official.AbsolutePath;
        if (!path.StartsWith(RepoPrefix, StringComparison.OrdinalIgnoreCase)) return [];
        var target = "https://github.com" + path + official.Query;
        return MirrorHosts.Select(host => new Uri("https://" + host + "/" + target)).ToArray();
    }

    public static ReleaseAsset Choose(string json, string os, string arch, bool preferRpm = false)
    {
        if (os is not ("macos" or "windows" or "linux"))
            throw new InvalidOperationException("这个系统不能用这个安装器");
        if (arch is not ("arm64" or "x64"))
            throw new InvalidOperationException("这个处理器架构没有对应的安装包");
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var tag = root.TryGetProperty("tag_name", out var tagEl) ? tagEl.GetString() ?? "" : "";
        var version = tag.StartsWith('v') || tag.StartsWith('V') ? tag[1..] : tag;
        if (!root.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
            throw new InvalidOperationException("发布页里没有安装包");

        var found = new List<ReleaseAsset>();
        foreach (var asset in assets.EnumerateArray())
        {
            var name = asset.TryGetProperty("name", out var nameEl) ? nameEl.GetString() ?? "" : "";
            var urlText = asset.TryGetProperty("browser_download_url", out var urlEl) ? urlEl.GetString() ?? "" : "";
            var size = asset.TryGetProperty("size", out var sizeEl) && sizeEl.TryGetInt64(out var n) ? n : 0;
            var match = Name.Match(name);
            if (!match.Success) continue;
            if (!string.Equals(match.Groups[1].Value, os, StringComparison.Ordinal)) continue;
            if (!string.Equals(match.Groups[2].Value, arch, StringComparison.Ordinal)) continue;
            if (!Uri.TryCreate(urlText, UriKind.Absolute, out var uri) || !IsAllowed(uri)) continue;
            if (size <= 0 || size > MaxBytes) continue;
            found.Add(new ReleaseAsset(name, uri, size, version, Digest(asset)));
        }

        var ext = os switch
        {
            "macos" => ".pkg",
            "windows" => ".msi",
            _ => preferRpm ? ".rpm" : ".deb"
        };
        var picked = found.Where(a => a.Name.EndsWith(ext, StringComparison.OrdinalIgnoreCase)).ToList();
        if (picked.Count == 0 && os == "linux" && !preferRpm)
            picked = found.Where(a => a.Name.EndsWith(".rpm", StringComparison.OrdinalIgnoreCase)).ToList();
        if (version.Length > 0)
        {
            var same = picked.Where(a => a.Name.Contains(version, StringComparison.Ordinal)).ToList();
            if (same.Count > 0) picked = same;
        }
        if (picked.Count != 1)
            throw new InvalidOperationException("没有找到这个系统的 Proxima ML 安装包");
        return picked[0];
    }

    private static string Digest(JsonElement asset)
    {
        if (!asset.TryGetProperty("digest", out var element)) return "";
        var text = element.GetString() ?? "";
        if (!text.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase)) return "";
        var hex = text[7..];
        if (hex.Length != 64 || !hex.All(Uri.IsHexDigit)) return "";
        return hex.ToLowerInvariant();
    }

    public static (string Os, string Arch) Current()
    {
        var os = OperatingSystem.IsMacOS() ? "macos"
            : OperatingSystem.IsWindows() ? "windows"
            : OperatingSystem.IsLinux() ? "linux"
            : "";
        var archName = System.Runtime.InteropServices.RuntimeInformation.OSArchitecture;
        var arch = archName == System.Runtime.InteropServices.Architecture.Arm64 ? "arm64"
            : archName == System.Runtime.InteropServices.Architecture.X64 ? "x64"
            : "";
        return (os, arch);
    }
}

/// <summary>第五个字段是 GitHub API 给出的 sha256，没有就为空。</summary>
public sealed record ReleaseAsset(string Name, Uri Url, long Size, string Version, string Sha256 = "");
