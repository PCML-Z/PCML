using System.Text.Json;
using System.Text.RegularExpressions;

namespace Pmcl.Installer;

/// <summary>
/// 从 GitHub Release 里挑当前系统的安装包。只接受官方仓库的 https 地址。
/// </summary>
public static class ReleaseChooser
{
    public const string LatestUrl = "https://api.github.com/repos/PCML-Z/PCML/releases/latest";
    public const long MaxBytes = 2L * 1024 * 1024 * 1024;

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
            found.Add(new ReleaseAsset(name, uri, size, version));
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
            throw new InvalidOperationException("没有找到这个系统的 PMCL 安装包");
        return picked[0];
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

public sealed record ReleaseAsset(string Name, Uri Url, long Size, string Version);
