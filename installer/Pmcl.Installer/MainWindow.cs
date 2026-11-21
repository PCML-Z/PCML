using System.Diagnostics;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Threading;
using Pmcl.Installer.Ui;
using Path = System.IO.Path;

namespace Pmcl.Installer;

/// <summary>下载当前系统的 PMCL 安装包，再交给系统安装。</summary>
public sealed class MainWindow : Window
{
    private enum Phase
    {
        Checking,
        Ready,
        Downloading,
        Installing,
        Done,
        Error
    }

    private static readonly Uri IconUri = new("avares://Pmcl.Installer/Assets/pmcl_icon.png");

    private readonly TextBlock _headline = Tokens.Text("", 20, Tokens.Ink, FontWeight.SemiBold);
    private readonly TextBlock _sub = Tokens.Text("", 12, Tokens.InkMuted);
    private readonly TextBlock _percent = Tokens.Text("", 11.5, Tokens.Primary, FontWeight.SemiBold);
    private readonly TextBlock _bytes = Tokens.Text("", 11, Tokens.InkMuted);
    private readonly TextBlock _speed = Tokens.Text("", 11, Tokens.InkMuted);
    private readonly Meter _bar = new();
    private readonly StackPanel _meters = new();
    private readonly FlatButton _go = new("下载并安装", primary: true);
    private readonly FlatButton _cancel = new("取消", primary: false) { IsVisible = false };
    private readonly StepMark[] _steps = [new("检查版本"), new("下载"), new("安装")];
    private readonly TextBlock _version = Tokens.Text("", 11, Tokens.InkMuted);
    private readonly TextBlock _file = Tokens.Text("正在挑选安装包", 11, Tokens.InkMuted);

    private readonly Queue<(long Bytes, long Ticks)> _speedWindow = new();
    private readonly CancellationTokenSource _lifetime = new();
    private CancellationTokenSource _run = new();
    private HttpClient? _http;
    private ReleaseAsset? _asset;
    private string _platform = "";
    private Phase _phase = Phase.Checking;
    private bool _busy;
    private bool _closing;

    public MainWindow()
    {
        Title = "PMCL 安装器";
        Width = 720;
        Height = 460;
        CanResize = false;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = Brushes.Transparent;
        TransparencyLevelHint = [WindowTransparencyLevel.AcrylicBlur, WindowTransparencyLevel.Blur];
        TransparencyBackgroundFallback = new SolidColorBrush(Color.Parse("#F5F7FA"));

        // macOS 把标题栏并进内容区；Windows/Linux 保留原生标题栏，免得没有关闭按钮。
        if (OperatingSystem.IsMacOS())
        {
            ExtendClientAreaToDecorationsHint = true;
            ExtendClientAreaChromeHints = Avalonia.Platform.ExtendClientAreaChromeHints.PreferSystemChrome;
            ExtendClientAreaTitleBarHeightHint = -1;
        }

        _percent.Width = 42;
        _percent.TextAlignment = TextAlignment.Right;
        _percent.HorizontalAlignment = HorizontalAlignment.Right;
        _sub.TextWrapping = TextWrapping.Wrap;
        _sub.MaxWidth = 520;
        _sub.HorizontalAlignment = HorizontalAlignment.Left;

        var card = BuildCard();
        var footer = BuildFooter();
        var page = new Grid
        {
            Margin = new Thickness(28, 36, 28, 18),
            RowDefinitions = new RowDefinitions("Auto,*,Auto"),
            Opacity = 0
        };
        page.Children.Add(BuildHeader());
        page.Children.Add(card);
        page.Children.Add(footer);
        Grid.SetRow(card, 1);
        card.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetRow(footer, 2);

        Content = new Panel { Children = { BuildBackdrop(), page } };

        _go.Click += (_, _) => OnPrimary();
        _cancel.Click += (_, _) => CancelRun();

        Opened += async (_, _) =>
        {
            _ = FadeIn.RunAsync(page);
            await LoadAsync();
        };
        Closing += (_, _) =>
        {
            _closing = true;
            _lifetime.Cancel();
        };
        KeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape && !_busy) Close();
            if (e.Key != Key.Enter) return;
            if (_phase == Phase.Ready) _ = InstallAsync();
            else if (_phase == Phase.Done) Close();
        };

        SetPhase(Phase.Checking);
    }

    private static readonly Animation FadeIn = new()
    {
        Duration = TimeSpan.FromMilliseconds(240),
        Easing = new LinearEasing(),
        FillMode = FillMode.Forward,
        Children =
        {
            new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 0d) } },
            new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 1d) } }
        }
    };

    // ---------- 布局 ----------

    /// <summary>背景只有 PMCL 的浅灰底和一枚极淡的线稿图标水印，不用彩色光斑。</summary>
    private static Control BuildBackdrop()
    {
        var watermark = new Image
        {
            Source = new Bitmap(AssetLoader.Open(IconUri)),
            Width = 300,
            Height = 300,
            Opacity = 0.035,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, -40, -120)
        };

        return new Grid
        {
            ClipToBounds = true,
            Children =
            {
                new Border { Background = Tokens.WindowWash },
                watermark
            }
        };
    }

    private Control BuildHeader()
    {
        var mark = new Image
        {
            Source = new Bitmap(AssetLoader.Open(IconUri)),
            Width = 48,
            Height = 48,
            VerticalAlignment = VerticalAlignment.Center
        };

        var wordmark = new StackPanel
        {
            Margin = new Thickness(10, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Spacing = 2,
            Children =
            {
                Tokens.Text("PMCL", 16, Tokens.Ink, FontWeight.SemiBold),
                Tokens.Text("安装器", 11, Tokens.InkMuted)
            }
        };

        _version.IsVisible = false;
        _version.HorizontalAlignment = HorizontalAlignment.Right;

        var header = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"),
            Height = 48
        };
        header.Children.Add(mark);
        header.Children.Add(wordmark);
        Grid.SetColumn(wordmark, 1);
        header.Children.Add(_version);
        Grid.SetColumn(_version, 2);

        header.PointerPressed += (_, e) =>
        {
            if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) BeginMoveDrag(e);
        };
        return header;
    }

    private Control BuildCard()
    {
        var barRow = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            Margin = new Thickness(0, 0, 0, 8)
        };
        _bar.VerticalAlignment = VerticalAlignment.Center;
        barRow.Children.Add(_bar);
        barRow.Children.Add(_percent);
        Grid.SetColumn(_percent, 1);
        _percent.Margin = new Thickness(12, 0, 0, 0);

        var metaRow = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        metaRow.Children.Add(_bytes);
        metaRow.Children.Add(_speed);
        Grid.SetColumn(_speed, 1);
        _speed.HorizontalAlignment = HorizontalAlignment.Right;

        _meters.Children.Add(barRow);
        _meters.Children.Add(metaRow);

        var steps = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 14
        };
        foreach (var step in _steps) steps.Children.Add(step);

        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
            HorizontalAlignment = HorizontalAlignment.Right,
            Children = { _cancel, _go }
        };

        var content = new StackPanel
        {
            Spacing = 16,
            VerticalAlignment = VerticalAlignment.Center,
            Children =
            {
                new StackPanel { Spacing = 8, Children = { _headline, _sub } },
                _meters,
                steps,
                actions
            }
        };

        return new Border
        {
            Background = Tokens.Card,
            BorderBrush = Tokens.CardEdge,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(16),
            MinHeight = 240,
            Child = content
        };
    }

    private Control BuildFooter()
    {
        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        _file.TextTrimming = TextTrimming.CharacterEllipsis;
        row.Children.Add(_file);

        var hint = Tokens.Text("GitHub Releases · 官方安装包", 11, Tokens.InkMuted);
        hint.HorizontalAlignment = HorizontalAlignment.Right;
        hint.Margin = new Thickness(16, 0, 0, 0);
        row.Children.Add(hint);
        Grid.SetColumn(hint, 1);
        return row;
    }

    // ---------- 状态 ----------

    private void SetPhase(Phase phase, string? headline = null, string? sub = null)
    {
        var previous = _phase;
        _phase = phase;

        _bar.IsIndeterminate = phase is Phase.Checking or Phase.Installing;
        if (_asset is not null) _version.IsVisible = true;
        _cancel.IsVisible = phase == Phase.Downloading;
        _cancel.SetEnabled(true);
        _go.IsVisible = phase is not (Phase.Downloading or Phase.Installing);
        _meters.IsVisible = phase != Phase.Error;
        _headline.Foreground = new SolidColorBrush(phase switch
        {
            Phase.Done => Tokens.Secondary,
            Phase.Error => Tokens.Error,
            _ => Tokens.Ink
        });

        switch (phase)
        {
            case Phase.Checking:
                _headline.Text = headline ?? "正在找最新版本";
                _sub.Text = sub ?? "连一下 GitHub 发布页";
                _percent.Text = "";
                _bytes.Text = "";
                _speed.Text = "连接中";
                _bar.Value = 0;
                _go.Text = "下载并安装";
                _go.SetEnabled(false);
                Steps(0, 0);
                break;

            case Phase.Ready:
                _headline.Text = headline ?? (_asset is null ? "准备安装" : "找到 PMCL " + _asset.Version);
                _sub.Text = sub ?? (_asset is null
                    ? "会挑一个适合本机的安装包"
                    : _platform + " · 安装包 " + ProgressText.Bytes(_asset.Size));
                _percent.Text = "0%";
                _bytes.Text = _asset is null ? "" : ProgressText.Bytes(_asset.Size);
                _speed.Text = "下载后自动安装";
                _bar.Value = 0;
                _go.Text = "下载并安装";
                _go.SetEnabled(_asset != null && !_busy);
                Steps(1, -1);
                break;

            case Phase.Downloading:
                _headline.Text = headline ?? "正在下载";
                _sub.Text = sub ?? "下完会打开系统安装程序";
                _percent.Text = "0%";
                _bytes.Text = _asset is null ? "" : "0 B / " + ProgressText.Bytes(_asset.Size);
                _speed.Text = "";
                _bar.Value = 0;
                Steps(1, 1);
                break;

            case Phase.Installing:
                _headline.Text = headline ?? "正在安装";
                _sub.Text = sub ?? "系统弹窗里点确认就行";
                _percent.Text = "";
                _bytes.Text = "安装包已下载";
                _speed.Text = "等待系统授权";
                Steps(2, 2);
                break;

            case Phase.Done:
                _headline.Text = headline ?? "装好了";
                _sub.Text = sub ?? "现在可以从应用列表打开 PMCL";
                _percent.Text = "100%";
                _bytes.Text = "";
                _speed.Text = "";
                _bar.IsIndeterminate = false;
                _bar.Value = 100;
                _go.Text = "完成";
                _go.SetEnabled(true);
                Steps(3, -1);
                break;

            case Phase.Error:
                _headline.Text = headline ?? "没装上";
                _sub.Text = sub ?? "可以再试一次";
                _percent.Text = "";
                _bytes.Text = "";
                _speed.Text = "";
                _bar.Value = 0;
                _go.Text = "重试";
                _go.SetEnabled(!_busy);
                Steps(0, -1, Math.Max(0, StepOf(previous)));
                break;
        }
    }

    private static int StepOf(Phase phase) => phase switch
    {
        Phase.Checking => 0,
        Phase.Ready or Phase.Downloading => 1,
        Phase.Installing => 2,
        _ => -1
    };

    private void Steps(int done, int active, int failed = -1)
    {
        for (var i = 0; i < _steps.Length; i++)
        {
            var state = i == failed ? StepState.Failed
                : i < done ? StepState.Done
                : i == active ? StepState.Active
                : StepState.Pending;
            _steps[i].Set(state);
        }
    }

    private void OnPrimary()
    {
        switch (_phase)
        {
            case Phase.Done:
                Close();
                break;
            case Phase.Error:
                _ = _asset is null ? LoadAsync() : InstallAsync();
                break;
            case Phase.Ready:
                _ = InstallAsync();
                break;
        }
    }

    private void CancelRun()
    {
        _cancel.SetEnabled(false);
        _run.Cancel();
    }

    private void NewRun()
    {
        var next = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _run.Dispose();
        _run = next;
    }

    // ---------- 流程 ----------

    private async Task LoadAsync()
    {
        NewRun();
        var (os, arch) = ReleaseChooser.Current();
        _platform = PlatformLabel(os, arch);
        if (os.Length == 0 || arch.Length == 0)
        {
            SetPhase(Phase.Error, "这个系统装不了", "PMCL 支持 macOS、Windows 和 Linux");
            return;
        }

        SetPhase(Phase.Checking);
        try
        {
            _http ??= ReleaseDownloader.CreateClient();
            var json = await ReleaseDownloader.GetStringAsync(_http, new Uri(ReleaseChooser.LatestUrl), _run.Token);
            _asset = ReleaseChooser.Choose(json, os, arch, PackageInstall.PreferRpm());
            _version.Text = "版本 " + _asset.Version;
            _file.Text = _asset.Name + " · " + ProgressText.Bytes(_asset.Size);
            SetPhase(Phase.Ready);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e)
        {
            _asset = null;
            _file.Text = "还没拿到安装包";
            SetPhase(Phase.Error, "没找到最新版本", e.Message);
        }
    }

    private async Task InstallAsync()
    {
        if (_busy || _asset is null || _http is null) return;
        _busy = true;
        NewRun();
        var token = _run.Token;
        var asset = _asset;
        var dest = Path.Combine(Path.GetTempPath(), "pmcl-installer", asset.Name);
        var watch = new Stopwatch();

        try
        {
            SetPhase(Phase.Downloading);
            _speedWindow.Clear();
            watch.Start();
            var progress = new ThrottledProgress(got => Dispatcher.UIThread.Post(() =>
            {
                var now = Environment.TickCount64;
                _speedWindow.Enqueue((got, now));
                while (_speedWindow.Count > 2 && now - _speedWindow.Peek().Ticks > 2000)
                    _speedWindow.Dequeue();
                var oldest = _speedWindow.Peek();
                var span = Math.Max(0.001, (now - oldest.Ticks) / 1000d);
                var speed = Math.Max(0, (got - oldest.Bytes) / span);

                _bar.Value = Math.Min(100, got * 100d / asset.Size);
                _percent.Text = ProgressText.Percent(got, asset.Size);
                _bytes.Text = ProgressText.Bytes(got) + " / " + ProgressText.Bytes(asset.Size);
                _speed.Text = speed > 1
                    ? ProgressText.Speed(speed) + " · " + ProgressText.Remaining((asset.Size - got) / speed)
                    : "正在连接";
            }), asset.Size);

            await ReleaseDownloader.DownloadAsync(_http, asset, dest, progress, token);
            watch.Stop();

            SetPhase(Phase.Installing);
            await PackageInstall.RunAsync(dest, token);
            SetPhase(Phase.Done);
        }
        catch (OperationCanceledException)
        {
            if (!_closing) SetPhase(Phase.Ready, "已取消", "想装的时候再点一次");
        }
        catch (Exception e)
        {
            SetPhase(Phase.Error, _phase == Phase.Installing ? "没装上" : "没下完", e.Message);
            _go.SetEnabled(true);
        }
        finally
        {
            _busy = false;
            if (_phase is Phase.Ready or Phase.Error) _go.SetEnabled(true);
        }
    }

    private static string PlatformLabel(string os, string arch) => (os, arch) switch
    {
        ("macos", "arm64") => "macOS · Apple 芯片",
        ("macos", "x64") => "macOS · Intel",
        ("windows", "arm64") => "Windows · ARM64",
        ("windows", "x64") => "Windows · x64",
        ("linux", "arm64") => "Linux · ARM64",
        ("linux", "x64") => "Linux · x64",
        _ => "当前系统"
    };

    /// <summary>进度回调很密，按 80ms 节流，避免刷爆界面线程。</summary>
    private sealed class ThrottledProgress(Action<long> report, long total) : IProgress<long>
    {
        private long _last;

        public void Report(long value)
        {
            var now = Environment.TickCount64;
            if (value < total && now - _last < 80) return;
            _last = now;
            report(value);
        }
    }
}
