using System.Net;
using System.Net.Http.Headers;
using Microsoft.Win32.SafeHandles;

namespace Pmcl.Installer;

/// <summary>
/// 下载安装包。走 GitHub 的 Range 请求分段并行拉取；服务器不支持时回退单连接。
/// 不跟随离开 GitHub 主机的重定向，写完后核对字节数。
/// </summary>
public static class ReleaseDownloader
{
    private const int BufferSize = 256 * 1024;
    private const long MinChunkBytes = 4L * 1024 * 1024;
    private const int MaxChunks = 8;
    private const long ReportStep = 256 * 1024;

    public static HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            MaxConnectionsPerServer = MaxChunks + 2
        };
        return new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(30) };
    }

    public static async Task<string> GetStringAsync(HttpClient http, Uri url, CancellationToken cancel)
    {
        using var response = await SendAsync(http, url, cancel);
        return await response.Content.ReadAsStringAsync(cancel);
    }

    public static async Task DownloadAsync(
        HttpClient http, ReleaseAsset asset, string destPath, IProgress<long>? progress, CancellationToken cancel)
    {
        var name = Path.GetFileName(destPath);
        if (!string.Equals(name, asset.Name, StringComparison.Ordinal))
            throw new InvalidOperationException("安装包文件名不正确");
        Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
        var partial = destPath + ".partial";

        var chunks = ChunkCount(asset.Size);
        var chunkSize = chunks == 1 ? asset.Size : (asset.Size + chunks - 1) / chunks;
        var firstEnd = Math.Min(asset.Size - 1, chunkSize - 1);

        using var first = await OpenAsync(http, asset.Url, 0, firstEnd, cancel);
        if (chunks > 1 && IsRange(first, 0, firstEnd, asset.Size))
        {
            try
            {
                await DownloadParallelAsync(http, asset, partial, progress, first, chunks, chunkSize, cancel);
                File.Move(partial, destPath, overwrite: true);
                return;
            }
            catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
            {
                // 某一段失败，整体回退到单连接。
            }
            catch (Exception)
            {
                // 分段不可用，整体回退到单连接。
            }
        }

        // 206 但不是预期分段：丢弃它，重新请求整包。
        var whole = first.StatusCode == HttpStatusCode.OK
            ? first
            : await SendAsync(http, asset.Url, cancel);
        await DownloadSingleAsync(whole, asset, partial, progress, cancel);
        File.Move(partial, destPath, overwrite: true);
    }

    private static int ChunkCount(long size) =>
        size < MinChunkBytes ? 1 : (int)Math.Min(MaxChunks, Math.Max(2, size / MinChunkBytes));

    private static bool IsRange(HttpResponseMessage response, long from, long to, long total)
    {
        if (response.StatusCode != HttpStatusCode.PartialContent) return false;
        var range = response.Content.Headers.ContentRange;
        return range is { Unit: "bytes" } && range.From == from && range.To == to && range.Length == total;
    }

    private static async Task DownloadParallelAsync(
        HttpClient http, ReleaseAsset asset, string partial, IProgress<long>? progress,
        HttpResponseMessage first, int chunks, long chunkSize, CancellationToken cancel)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        using var handle = File.OpenHandle(partial, FileMode.Create, FileAccess.Write, FileShare.None, FileOptions.Asynchronous);
        RandomAccess.SetLength(handle, asset.Size);

        long totalRead = 0;
        long lastReport = 0;
        void Count(long bytes)
        {
            var now = Interlocked.Add(ref totalRead, bytes);
            if (now - Volatile.Read(ref lastReport) < ReportStep && now != asset.Size) return;
            Interlocked.Exchange(ref lastReport, now);
            progress?.Report(now);
        }

        var tasks = new List<Task>();
        for (var i = 0; i < chunks; i++)
        {
            var start = i * chunkSize;
            if (start >= asset.Size) break;
            var end = Math.Min(asset.Size - 1, start + chunkSize - 1);

            var response = i == 0
                ? first
                : await OpenAsync(http, asset.Url, start, end, linked.Token);
            if (!IsRange(response, start, end, asset.Size))
            {
                response.Dispose();
                throw new InvalidOperationException("服务器不支持分段下载");
            }
            tasks.Add(CopyChunkAsync(response, handle, start, end - start + 1, Count, linked.Token));
        }

        await Task.WhenAll(tasks);
        if (Volatile.Read(ref totalRead) != asset.Size)
            throw new InvalidOperationException("安装包没有下完");
    }

    private static async Task CopyChunkAsync(
        HttpResponseMessage response, SafeFileHandle handle, long offset, long expected,
        Action<long> count, CancellationToken cancel)
    {
        using (response)
        {
            await using var input = await response.Content.ReadAsStreamAsync(cancel);
            var buffer = new byte[BufferSize];
            long got = 0;
            while (true)
            {
                var n = await input.ReadAsync(buffer, cancel);
                if (n == 0) break;
                got += n;
                if (got > expected) throw new InvalidOperationException("下载内容超过分段大小");
                await RandomAccess.WriteAsync(handle, buffer.AsMemory(0, n), offset + got, cancel);
                count(n);
            }
            if (got != expected) throw new InvalidOperationException("分段没有下完");
        }
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

    private static async Task<HttpResponseMessage> SendAsync(HttpClient http, Uri url, CancellationToken cancel) =>
        await OpenAsync(http, url, null, null, cancel);

    private static async Task<HttpResponseMessage> OpenAsync(
        HttpClient http, Uri url, long? from, long? to, CancellationToken cancel)
    {
        if (!ReleaseChooser.IsAllowed(url)) throw new InvalidOperationException("下载地址不在 GitHub");
        var current = url;
        for (var hop = 0; hop < 5; hop++)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, current);
            request.Headers.UserAgent.ParseAdd("PMCL-Installer");
            if (from.HasValue && to.HasValue)
                request.Headers.Range = new RangeHeaderValue(from.Value, to.Value);

            var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            if ((int)response.StatusCode is 301 or 302 or 303 or 307 or 308)
            {
                var next = response.Headers.Location;
                response.Dispose();
                if (next == null) throw new InvalidOperationException("下载被重定向到了别处");
                if (!next.IsAbsoluteUri) next = new Uri(current, next);
                if (!ReleaseChooser.IsAllowed(next)) throw new InvalidOperationException("下载被重定向到了别处");
                current = next;
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
