using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;

namespace Pmcl.Installer;

/// <summary>
/// 下载安装包。官方和镜像同时探测，能并发就并发；分段写同一个文件不同偏移。
/// 镜像只作为加速通道，下载完用官方 API 的 sha256 校验，不信任代理内容。
/// </summary>
public static class ReleaseDownloader
{
    private const int BufferSize = 256 * 1024;
    private const long MinChunkBytes = 4L * 1024 * 1024;
    private const int MaxChunks = 16;
    private const long ReportStep = 256 * 1024;
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(8);

    private sealed record Source(Uri Url, Func<Uri, bool> Allows);

    public static HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            MaxConnectionsPerServer = MaxChunks + 4,
            ConnectTimeout = ProbeTimeout
        };
        return new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(30) };
    }

    public static async Task<string> GetStringAsync(HttpClient http, Uri url, CancellationToken cancel)
    {
        using var response = await OpenAsync(http, new Source(url, ReleaseChooser.IsAllowed), null, null, cancel);
        return await response.Content.ReadAsStringAsync(cancel);
    }

    public static async Task DownloadAsync(
        HttpClient http, ReleaseAsset asset, string destPath, IProgress<long>? progress, CancellationToken cancel,
        Action<string>? onSource = null)
    {
        var name = Path.GetFileName(destPath);
        if (!string.Equals(name, asset.Name, StringComparison.Ordinal))
            throw new InvalidOperationException("安装包文件名不正确");
        Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
        var partial = destPath + ".partial";

        var chunks = ChunkCount(asset.Size);
        var sources = chunks == 1 ? [] : await ProbeSourcesAsync(http, asset, cancel);
        onSource?.Invoke(sources.Count switch
        {
            0 => "单连接下载",
            1 when ReleaseChooser.IsAllowed(sources[0].Url) => "官方直连",
            _ => "镜像加速"
        });
        if (sources.Count == 0)
        {
            using var response = await OpenAsync(
                http, new Source(asset.Url, ReleaseChooser.IsAllowed), null, null, cancel);
            await DownloadSingleAsync(response, asset, partial, progress, cancel);
        }
        else
        {
            var chunkSize = (asset.Size + chunks - 1) / chunks;
            await DownloadParallelAsync(http, asset, partial, progress, sources, chunks, chunkSize, cancel);
        }

        await VerifyAsync(partial, asset.Sha256, cancel);
        File.Move(partial, destPath, overwrite: true);
    }

    private static int ChunkCount(long size) =>
        size < MinChunkBytes ? 1 : (int)Math.Min(MaxChunks, Math.Max(2, size / MinChunkBytes));

    private static async Task<List<Source>> ProbeSourcesAsync(
        HttpClient http, ReleaseAsset asset, CancellationToken cancel)
    {
        var official = new Source(asset.Url, ReleaseChooser.IsAllowed);
        var mirrored = ReleaseChooser.MirrorUrls(asset.Url).Select(url =>
            new Source(url, u => ReleaseChooser.IsMirrorAllowed(u) || ReleaseChooser.IsAllowed(u)));
        // 没有官方 sha256 就只用官方源，不把内容校验建立在信任代理上。
        var candidates = asset.Sha256.Length == 64
            ? new[] { official }.Concat(mirrored).ToArray()
            : new[] { official };

        var checkedSources = await Task.WhenAll(candidates.Select(async source =>
        {
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancel);
                timeout.CancelAfter(ProbeTimeout);
                using var response = await OpenAsync(http, source, 0, 0, timeout.Token);
                return IsRange(response, 0, 0, asset.Size) ? source : null;
            }
            catch (Exception)
            {
                return null;
            }
        }));
        return checkedSources.Where(s => s is not null).Select(s => s!).ToList();
    }

    private static bool IsRange(HttpResponseMessage response, long from, long to, long total)
    {
        if (response.StatusCode != HttpStatusCode.PartialContent) return false;
        var range = response.Content.Headers.ContentRange;
        return range is { Unit: "bytes" } && range.From == from && range.To == to && range.Length == total;
    }

    private static async Task DownloadParallelAsync(
        HttpClient http, ReleaseAsset asset, string partial, IProgress<long>? progress,
        IReadOnlyList<Source> sources, int chunks, long chunkSize, CancellationToken cancel)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        using var handle = File.OpenHandle(partial, FileMode.Create, FileAccess.Write, FileShare.None, FileOptions.Asynchronous);
        RandomAccess.SetLength(handle, asset.Size);

        var received = new long[chunks];
        long reported = 0;
        long lastThrottle = 0;
        var gate = new object();

        void Count(int index, long bytes)
        {
            long sum;
            lock (gate)
            {
                received[index] += bytes;
                sum = received.Sum();
                if (sum <= reported) return;
                if (sum - lastThrottle < ReportStep && sum != asset.Size) return;
                lastThrottle = sum;
                reported = sum;
            }
            progress?.Report(sum);
        }

        var tasks = new List<Task>();
        for (var i = 0; i < chunks; i++)
        {
            var start = i * chunkSize;
            if (start >= asset.Size) break;
            var end = Math.Min(asset.Size - 1, start + chunkSize - 1);
            tasks.Add(DownloadChunkAsync(http, sources, i, start, end, asset.Size, handle, Count, gate, received, linked.Token, cancel));
        }

        await Task.WhenAll(tasks);
        progress?.Report(asset.Size);
    }

    private static async Task DownloadChunkAsync(
        HttpClient http, IReadOnlyList<Source> sources, int index, long start, long end, long total,
        SafeFileHandle handle, Action<int, long> count, object gate, long[] received,
        CancellationToken linkedCancel, CancellationToken cancel)
    {
        for (var attempt = 0; attempt < sources.Count; attempt++)
        {
            var source = sources[(index + attempt) % sources.Count];
            try
            {
                using var response = await OpenAsync(http, source, start, end, linkedCancel);
                if (!IsRange(response, start, end, total))
                    throw new InvalidOperationException("下载源不支持分段");
                await CopyChunkAsync(response, handle, start, end - start + 1, bytes => count(index, bytes), linkedCancel);
                return;
            }
            catch (OperationCanceledException) when (cancel.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception)
            {
                lock (gate) received[index] = 0;
                if (attempt == sources.Count - 1) throw;
                try { await Task.Delay(120, linkedCancel); }
                catch (OperationCanceledException) when (!cancel.IsCancellationRequested) { }
            }
        }
    }

    private static async Task CopyChunkAsync(
        HttpResponseMessage response, SafeFileHandle handle, long offset, long expected,
        Action<long> count, CancellationToken cancel)
    {
        await using var input = await response.Content.ReadAsStreamAsync(cancel);
        var buffer = new byte[BufferSize];
        long got = 0;
        while (true)
        {
            var n = await input.ReadAsync(buffer, cancel);
            if (n == 0) break;
            var position = offset + got;
            got += n;
            if (got > expected) throw new InvalidOperationException("下载内容超过分段大小");
            await RandomAccess.WriteAsync(handle, buffer.AsMemory(0, n), position, cancel);
            count(n);
        }
        if (got != expected) throw new InvalidOperationException("分段没有下完");
    }

    private static async Task DownloadSingleAsync(
        HttpResponseMessage response, ReleaseAsset asset, string partial,
        IProgress<long>? progress, CancellationToken cancel)
    {
        var length = response.Content.Headers.ContentLength;
        if (length != asset.Size)
            throw new InvalidOperationException("安装包大小和发布页不一致");

        await using var output = new FileStream(
            partial, FileMode.Create, FileAccess.Write, FileShare.None, BufferSize,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        await using var input = await response.Content.ReadAsStreamAsync(cancel);
        var buffer = new byte[BufferSize];
        long got = 0;
        while (true)
        {
            var n = await input.ReadAsync(buffer, cancel);
            if (n == 0) break;
            got += n;
            if (got > asset.Size) throw new InvalidOperationException("安装包比发布页上的更大");
            await output.WriteAsync(buffer.AsMemory(0, n), cancel);
            progress?.Report(got);
        }
        if (got != asset.Size) throw new InvalidOperationException("安装包没有下完");
    }

    private static async Task VerifyAsync(string path, string expected, CancellationToken cancel)
    {
        if (expected.Length == 0) return;
        await using var input = new FileStream(
            path, FileMode.Open, FileAccess.Read, FileShare.Read, BufferSize,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        var hash = await SHA256.HashDataAsync(input, cancel);
        var actual = Convert.ToHexString(hash).ToLowerInvariant();
        if (!string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("安装包校验失败，已丢弃");
    }

    private static async Task<HttpResponseMessage> OpenAsync(
        HttpClient http, Source source, long? from, long? to, CancellationToken cancel)
    {
        var url = source.Url;
        if (!source.Allows(url)) throw new InvalidOperationException("下载地址不在允许范围");
        for (var hop = 0; hop < 5; hop++)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.UserAgent.ParseAdd("PMCL-Installer");
            if (from.HasValue && to.HasValue)
                request.Headers.Range = new RangeHeaderValue(from.Value, to.Value);

            var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            if ((int)response.StatusCode is 301 or 302 or 303 or 307 or 308)
            {
                var next = response.Headers.Location;
                response.Dispose();
                if (next == null) throw new InvalidOperationException("下载被重定向到了别处");
                if (!next.IsAbsoluteUri) next = new Uri(url, next);
                if (!source.Allows(next)) throw new InvalidOperationException("下载被重定向到了别处");
                url = next;
                continue;
            }
            if (!response.IsSuccessStatusCode)
            {
                response.Dispose();
                throw new InvalidOperationException("下载失败：" + (int)response.StatusCode);
            }
            return response;
        }
        throw new InvalidOperationException("下载被重定向了太多次");
    }
}
