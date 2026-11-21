using System.Globalization;

namespace Pmcl.Installer;

/// <summary>把下载进度换算成界面上显示的短文案。</summary>
public static class ProgressText
{
    public static string Bytes(long bytes)
    {
        if (bytes < 0) bytes = 0;
        if (bytes >= 1024L * 1024 * 1024)
            return (bytes / 1024d / 1024 / 1024).ToString("0.00", CultureInfo.InvariantCulture) + " GB";
        if (bytes >= 1024L * 1024)
            return (bytes / 1024d / 1024).ToString("0.0", CultureInfo.InvariantCulture) + " MB";
        if (bytes >= 1024)
            return (bytes / 1024d).ToString("0", CultureInfo.InvariantCulture) + " KB";
        return bytes.ToString(CultureInfo.InvariantCulture) + " B";
    }

    /// <summary>向下取整，没下完不会显示 100%。</summary>
    public static string Percent(long done, long total)
    {
        if (total <= 0) return "0%";
        var value = done <= 0 ? 0 : (int)Math.Min(100, done * 100L / total);
        return value.ToString(CultureInfo.InvariantCulture) + "%";
    }

    public static string Speed(double bytesPerSecond)
    {
        if (bytesPerSecond < 1) return "";
        return Bytes((long)Math.Round(bytesPerSecond)) + "/s";
    }

    public static string Remaining(double seconds)
    {
        if (seconds <= 1) return "即将完成";
        var total = (int)Math.Ceiling(seconds);
        if (total < 60) return "约 " + total.ToString(CultureInfo.InvariantCulture) + " 秒";
        if (total < 3600)
        {
            var minutes = total / 60;
            var rest = total % 60;
            if (rest == 0 || minutes >= 10) return "约 " + minutes.ToString(CultureInfo.InvariantCulture) + " 分";
            return "约 " + minutes.ToString(CultureInfo.InvariantCulture) + " 分 " + rest.ToString(CultureInfo.InvariantCulture) + " 秒";
        }
        return "约 " + (total / 3600).ToString(CultureInfo.InvariantCulture) + " 小时";
    }
}
