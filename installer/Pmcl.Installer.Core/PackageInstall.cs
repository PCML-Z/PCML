using System.Diagnostics;

namespace Pmcl.Installer;

/// <summary>
/// 把已经核对过的安装包交给系统安装程序。macOS 和 Windows 会弹出系统授权。
/// 路径里不能有引号，避免拼进安装命令。
/// </summary>
public static class PackageInstall
{
    public static bool PreferRpm()
    {
        if (!OperatingSystem.IsLinux()) return false;
        if (File.Exists("/usr/bin/dpkg")) return false;
        return File.Exists("/usr/bin/rpm");
    }

    public static (string FileName, IReadOnlyList<string> Args) Plan(string os, string packagePath, bool hasPkexec)
    {
        if (packagePath.IndexOfAny(['\'', '"', '\n', '\r']) >= 0)
            throw new InvalidOperationException("安装包路径不能包含引号");
        var ext = Path.GetExtension(packagePath).ToLowerInvariant();
        if (os == "macos" && ext == ".pkg")
        {
            var quoted = "'" + packagePath + "'";
            return ("/usr/bin/osascript",
            [
                "-e",
                "do shell script \"installer -pkg " + quoted + " -target /\" with administrator privileges"
            ]);
        }
        if (os == "windows" && ext == ".msi")
            return ("msiexec.exe", ["/i", packagePath, "/qb"]);
        if (os == "linux" && ext is ".deb" or ".rpm")
        {
            var helper = hasPkexec ? "/usr/bin/pkexec" : "/usr/bin/sudo";
            if (ext == ".deb") return (helper, ["dpkg", "-i", packagePath]);
            return (helper, ["rpm", "-U", packagePath]);
        }
        throw new InvalidOperationException("这个安装包不能在当前系统上安装");
    }

    public static async Task RunAsync(string packagePath, CancellationToken cancel)
    {
        var name = Path.GetFileName(packagePath);
        if (name.Contains('/') || name.Contains('\\') || !File.Exists(packagePath))
            throw new InvalidOperationException("安装包不在预期的位置");
        var os = ReleaseChooser.Current().Os;
        var plan = Plan(os, packagePath, File.Exists("/usr/bin/pkexec"));
        var psi = new ProcessStartInfo
        {
            FileName = plan.FileName,
            UseShellExecute = false,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            CreateNoWindow = true
        };
        foreach (var arg in plan.Args) psi.ArgumentList.Add(arg);
        using var process = new Process { StartInfo = psi };
        if (!process.Start()) throw new InvalidOperationException("没有启动系统安装程序");
        var stdout = process.StandardOutput.ReadToEndAsync(cancel);
        var stderr = process.StandardError.ReadToEndAsync(cancel);
        await process.WaitForExitAsync(cancel);
        _ = await stdout;
        var err = await stderr;
        if (process.ExitCode != 0)
        {
            var detail = string.IsNullOrWhiteSpace(err) ? "" : "：" + err.Trim();
            throw new InvalidOperationException("安装没有完成" + detail);
        }
    }
}
