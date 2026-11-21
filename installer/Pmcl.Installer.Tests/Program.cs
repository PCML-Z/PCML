namespace Pmcl.Installer.Tests;

internal static class Program
{
    public static int Main()
    {
        var failed = 0;
        failed += Check(PicksPkgForMacArm, "mac arm pkg");
        failed += Check(PicksMsiForWindows, "windows msi");
        failed += Check(PrefersDebThenRpm, "linux package");
        failed += Check(RejectsForeignHost, "foreign host");
        failed += Check(RejectsJar, "jar");
        failed += Check(PlansNativeInstaller, "install plan");
        failed += Check(FormatsProgress, "progress text");
        return failed == 0 ? 0 : 1;
    }

    private static int Check(Action test, string name)
    {
        try
        {
            test();
            Console.WriteLine("ok " + name);
            return 0;
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("fail " + name + ": " + e.Message);
            return 1;
        }
    }

    private static string Release(params string[] assets) =>
        "{\"tag_name\":\"v2.10.2\",\"assets\":[" + string.Join(',', assets) + "]}";

    private static string Asset(string name, string url, long size) =>
        "{\"name\":\"" + name + "\",\"browser_download_url\":\"" + url + "\",\"size\":" + size + "}";

    private static void PicksPkgForMacArm()
    {
        var json = Release(
            Asset("pmcl-2.10.2-macos-arm64.pkg", "https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-macos-arm64.pkg", 1000),
            Asset("pmcl-2.10.2-windows-x64.msi", "https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-windows-x64.msi", 1000));
        var picked = ReleaseChooser.Choose(json, "macos", "arm64");
        if (picked.Name != "pmcl-2.10.2-macos-arm64.pkg" || picked.Version != "2.10.2")
            throw new InvalidOperationException(picked.Name);
    }

    private static void PicksMsiForWindows()
    {
        var json = Release(
            Asset("pmcl-2.10.2-windows-x64.msi", "https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-windows-x64.msi", 2000),
            Asset("pmcl-2.10.2-windows-x64.jar", "https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-windows-x64.jar", 2000));
        var picked = ReleaseChooser.Choose(json, "windows", "x64");
        if (!picked.Name.EndsWith(".msi", StringComparison.Ordinal)) throw new InvalidOperationException(picked.Name);
    }

    private static void PrefersDebThenRpm()
    {
        var json = Release(
            Asset("pmcl-2.10.2-linux-x64.rpm", "https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-linux-x64.rpm", 10),
            Asset("pmcl-2.10.2-linux-x64.deb", "https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-linux-x64.deb", 10));
        var deb = ReleaseChooser.Choose(json, "linux", "x64");
        if (!deb.Name.EndsWith(".deb", StringComparison.Ordinal)) throw new InvalidOperationException(deb.Name);
        var rpm = ReleaseChooser.Choose(json, "linux", "x64", preferRpm: true);
        if (!rpm.Name.EndsWith(".rpm", StringComparison.Ordinal)) throw new InvalidOperationException(rpm.Name);
    }

    private static void RejectsForeignHost()
    {
        var json = Release(Asset(
            "pmcl-2.10.2-macos-arm64.pkg",
            "https://evil.example/pmcl-2.10.2-macos-arm64.pkg",
            10));
        try
        {
            ReleaseChooser.Choose(json, "macos", "arm64");
        }
        catch (InvalidOperationException)
        {
            return;
        }
        throw new InvalidOperationException("accepted foreign host");
    }

    private static void RejectsJar()
    {
        var json = Release(Asset(
            "pmcl-2.10.2-macos-arm64.jar",
            "https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-macos-arm64.jar",
            10));
        try
        {
            ReleaseChooser.Choose(json, "macos", "arm64");
        }
        catch (InvalidOperationException)
        {
            return;
        }
        throw new InvalidOperationException("accepted jar");
    }

    private static void PlansNativeInstaller()
    {
        var mac = PackageInstall.Plan("macos", "/tmp/pmcl-installer/pmcl-2.10.2-macos-arm64.pkg", false);
        if (mac.FileName != "/usr/bin/osascript" || !mac.Args[1].Contains("installer -pkg", StringComparison.Ordinal))
            throw new InvalidOperationException(mac.FileName);
        var win = PackageInstall.Plan("windows", @"C:\Temp\pmcl-2.10.2-windows-x64.msi", false);
        if (win.FileName != "msiexec.exe" || win.Args[0] != "/i")
            throw new InvalidOperationException(win.FileName);
        var linux = PackageInstall.Plan("linux", "/tmp/pmcl-2.10.2-linux-x64.deb", true);
        if (linux.FileName != "/usr/bin/pkexec" || linux.Args[0] != "dpkg")
            throw new InvalidOperationException(linux.FileName);
        if (ReleaseChooser.IsAllowed(new Uri("http://github.com/PCML-Z/PCML/releases/latest")))
            throw new InvalidOperationException("http was allowed");
    }

    private static void FormatsProgress()
    {
        Expect(ProgressText.Bytes(0), "0 B");
        Expect(ProgressText.Bytes(1024), "1 KB");
        Expect(ProgressText.Bytes(29_500_000), "28.1 MB");
        Expect(ProgressText.Bytes(3L * 1024 * 1024 * 1024), "3.00 GB");

        Expect(ProgressText.Percent(0, 100), "0%");
        Expect(ProgressText.Percent(50, 100), "50%");
        Expect(ProgressText.Percent(99, 100), "99%");
        Expect(ProgressText.Percent(100, 100), "100%");
        Expect(ProgressText.Percent(1, 3), "33%");
        Expect(ProgressText.Percent(5, 0), "0%");

        Expect(ProgressText.Speed(3.2 * 1024 * 1024), "3.2 MB/s");
        Expect(ProgressText.Speed(0), "");

        Expect(ProgressText.Remaining(0.4), "即将完成");
        Expect(ProgressText.Remaining(5.2), "约 6 秒");
        Expect(ProgressText.Remaining(75), "约 1 分 15 秒");
        Expect(ProgressText.Remaining(600), "约 10 分");
        Expect(ProgressText.Remaining(7200), "约 2 小时");
    }

    private static void Expect(string actual, string expected)
    {
        if (!string.Equals(actual, expected, StringComparison.Ordinal))
            throw new InvalidOperationException("'" + actual + "' != '" + expected + "'");
    }
}
