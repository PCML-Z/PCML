using System.Net;
using System.Net.Http.Headers;

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
        failed += Check(DownloadsInChunks, "chunked download");
        failed += Check(FallsBackWithoutRange, "range fallback");
        failed += Check(ParsesDigestAndMirrors, "digest and mirrors");
        failed += Check(RejectsCorruptedDownload, "sha256 verification");
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

    private static ReleaseAsset TestAsset(long size) => new(
        "pmcl-2.10.2-macos-arm64.pkg",
        new Uri("https://github.com/PCML-Z/PCML/releases/download/v2.10.2/pmcl-2.10.2-macos-arm64.pkg"),
        size, "2.10.2");

    private static void DownloadsInChunks()
    {
        var data = new byte[9 * 1024 * 1024];
        new Random(42).NextBytes(data);
        var handler = new FakeHandler(data, range: true);
        using var http = new HttpClient(handler);

        RunDownload(http, data, out var dest, out var reported);
        if (handler.Requests < 2)
            throw new InvalidOperationException("expected parallel chunks, got " + handler.Requests);
        VerifyDownload(data, dest, reported);
    }

    private static void FallsBackWithoutRange()
    {
        var data = new byte[3 * 1024 * 1024];
        new Random(7).NextBytes(data);
        var handler = new FakeHandler(data, range: false);
        using var http = new HttpClient(handler);

        RunDownload(http, data, out var dest, out var reported);
        if (handler.Requests != 1)
            throw new InvalidOperationException("expected single fallback request, got " + handler.Requests);
        VerifyDownload(data, dest, reported);
    }

    private static void RunDownload(HttpClient http, byte[] data, out string dest, out long reported)
    {
        var dir = Path.Combine(Path.GetTempPath(), "pmcl-dl-test-" + Guid.NewGuid().ToString("N"));
        var asset = TestAsset(data.Length);
        dest = Path.Combine(dir, asset.Name);
        long seen = 0;
        ReleaseDownloader.DownloadAsync(
            http, asset, dest, new Progress<long>(v => seen = Math.Max(seen, v)), CancellationToken.None)
            .GetAwaiter().GetResult();
        reported = seen;
    }

    private static void VerifyDownload(byte[] expected, string dest, long reported)
    {
        if (reported != expected.Length)
            throw new InvalidOperationException("progress " + reported + " != " + expected.Length);
        if (!File.ReadAllBytes(dest).AsSpan().SequenceEqual(expected))
            throw new InvalidOperationException("downloaded bytes differ");
        Directory.Delete(Path.GetDirectoryName(dest)!, recursive: true);
    }

    private sealed class FakeHandler(byte[] data, bool range) : HttpMessageHandler
    {
        private int _requests;

        public int Requests => Volatile.Read(ref _requests);

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref _requests);
            var part = request.Headers.Range?.Ranges.FirstOrDefault();
            if (range && part is not null)
            {
                var from = (int)(part.From ?? 0);
                var to = (int)(part.To ?? data.Length - 1);
                var sliced = new ByteArrayContent(data, from, to - from + 1);
                sliced.Headers.ContentRange = new ContentRangeHeaderValue(from, to, data.Length);
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = sliced });
            }

            var full = new ByteArrayContent(data);
            full.Headers.ContentLength = data.Length;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = full });
        }
    }

    private static void ParsesDigestAndMirrors()
    {
        const string digest = "b9bce8205c14a1590d629f7d1e5d3fe18e38635956e7d4b05e482eee7baa6bad";
        var json = Release(
            Asset("pmcl-2.11.1-macos-arm64.pkg",
                "https://github.com/PCML-Z/PCML/releases/download/v2.11.1/pmcl-2.11.1-macos-arm64.pkg", 1000)
            .Replace("\"size\":1000}", "\"size\":1000,\"digest\":\"sha256:" + digest + "\"}"));
        var picked = ReleaseChooser.Choose(json, "macos", "arm64");
        if (!string.Equals(picked.Sha256, digest, StringComparison.Ordinal))
            throw new InvalidOperationException("digest not parsed");

        var mirrors = ReleaseChooser.MirrorUrls(picked.Url);
        if (mirrors.Count != 2) throw new InvalidOperationException("mirror count " + mirrors.Count);
        if (!mirrors.All(ReleaseChooser.IsMirrorAllowed))
            throw new InvalidOperationException("mirror not allowed");
        if (ReleaseChooser.IsMirrorAllowed(new Uri("https://gh-proxy.com/https://evil.example/x.pkg")))
            throw new InvalidOperationException("foreign mirror allowed");
    }

    private static void RejectsCorruptedDownload()
    {
        var good = new byte[2 * 1024 * 1024];
        new Random(11).NextBytes(good);
        var expectedHash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(good)).ToLowerInvariant();
        var bad = new byte[good.Length];
        new Random(12).NextBytes(bad);

        var handler = new FakeHandler(bad, range: false);
        using var http = new HttpClient(handler);
        var asset = TestAsset(good.Length) with { Sha256 = expectedHash };
        var dir = Path.Combine(Path.GetTempPath(), "pmcl-dl-test-" + Guid.NewGuid().ToString("N"));
        var dest = Path.Combine(dir, asset.Name);
        try
        {
            ReleaseDownloader.DownloadAsync(http, asset, dest, null, CancellationToken.None).GetAwaiter().GetResult();
        }
        catch (InvalidOperationException e) when (e.Message.Contains("校验失败", StringComparison.Ordinal))
        {
            if (File.Exists(dest)) throw new InvalidOperationException("corrupted file was kept");
            Directory.Delete(dir, true);
            return;
        }
        throw new InvalidOperationException("corrupt download was accepted");
    }

    private static void Expect(string actual, string expected)
    {
        if (!string.Equals(actual, expected, StringComparison.Ordinal))
            throw new InvalidOperationException("'" + actual + "' != '" + expected + "'");
    }
}
