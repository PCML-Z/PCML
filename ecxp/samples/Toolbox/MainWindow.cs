using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Pmcl.Ecxp;

namespace Pmcl.Toolbox;

/// <summary>左边实例，右边正在运行的游戏，底下是启动器事件。</summary>
public sealed class MainWindow : Window
{
    private static readonly bool Zh = PmclApp.Language.StartsWith("zh", StringComparison.OrdinalIgnoreCase);

    private readonly ListBox _instances = new();
    private readonly ListBox _games = new();
    private readonly ListBox _events = new() { MaxHeight = 150 };
    private readonly TextBlock _status = new() { Opacity = 0.7, VerticalAlignment = VerticalAlignment.Center };
    private readonly Button _launch = new() { Content = T("启动", "Launch") };
    private readonly Button _kill = new() { Content = T("结束", "Stop") };
    private readonly Button _refresh = new() { Content = T("刷新", "Refresh") };
    private readonly Button _notify = new() { Content = T("在启动器里发通知", "Notify in PMCL") };
    private readonly DispatcherTimer _poll = new() { Interval = TimeSpan.FromSeconds(3) };
    private PmclApp? _app;

    public MainWindow()
    {
        Title = "ECXP Toolbox";
        Width = 820;
        Height = 540;
        MinWidth = 560;
        MinHeight = 380;

        _launch.Classes.Add("accent");
        _launch.Click += async (_, _) => await LaunchSelected();
        _kill.Click += async (_, _) => await KillSelected();
        _refresh.Click += async (_, _) => await Refresh();
        _notify.Click += async (_, _) => await Run(() => _app!.Ui.NotifyAsync("ECXP Toolbox", T("来自 .NET 窗口的消息。", "Hello from a .NET window.")));
        _poll.Tick += async (_, _) => await RefreshGames();

        var title = new TextBlock { Text = "ECXP Toolbox", FontSize = 18, FontWeight = FontWeight.SemiBold };
        var header = new DockPanel { Margin = new Thickness(20, 16, 20, 8) };
        DockPanel.SetDock(_notify, Dock.Right);
        header.Children.Add(_notify);
        header.Children.Add(new StackPanel { Spacing = 2, Children = { title, _status } });

        var body = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,16,*"),
            Margin = new Thickness(20, 8, 20, 8),
        };
        var left = Section(T("实例", "Instances"), _instances, _launch, _refresh);
        var right = Section(T("正在运行的游戏", "Running games"), _games, _kill);
        Grid.SetColumn(right, 2);
        body.Children.Add(left);
        body.Children.Add(right);

        var log = new StackPanel
        {
            Margin = new Thickness(20, 0, 20, 16),
            Spacing = 6,
            Children = { Caption(T("启动器事件", "Launcher events")), _events },
        };

        var root = new DockPanel();
        DockPanel.SetDock(header, Dock.Top);
        DockPanel.SetDock(log, Dock.Bottom);
        root.Children.Add(header);
        root.Children.Add(log);
        root.Children.Add(body);
        Content = root;

        SetEnabled(false);
        Opened += async (_, _) => await Connect();
        Closed += async (_, _) =>
        {
            _poll.Stop();
            if (_app != null) await _app.DisposeAsync();
        };
    }

    private async Task Connect()
    {
        if (!PmclApp.IsHosted)
        {
            _status.Text = T("请从 PMCL 的 ECXP 页面打开这个应用。", "Open this app from the ECXP page in PMCL.");
            return;
        }
        try
        {
            _app = await PmclApp.ConnectAsync();
        }
        catch (Exception e)
        {
            _status.Text = T("连不上 PMCL：", "Cannot reach PMCL: ") + e.Message;
            return;
        }
        _status.Text = $"{_app.Info.Launcher} {_app.Info.LauncherVersion} · .NET {Environment.Version}";
        _app.ActivateRequested += () => Dispatcher.UIThread.Post(BringToFront);
        _app.ShutdownRequested += () => Dispatcher.UIThread.Post(Quit);
        _app.Disconnected += () => Dispatcher.UIThread.Post(Quit);
        _app.LauncherEvent += e => Dispatcher.UIThread.Post(() => OnLauncherEvent(e));
        SetEnabled(true);
        await Refresh();
        _poll.Start();
    }

    private async Task Refresh()
    {
        await Run(async () =>
        {
            var list = await _app!.Instances.ListAsync();
            _instances.ItemsSource = list.Select(i => new ListBoxItem
            {
                Tag = i,
                Content = Row(i.Name, string.Join(" · ", new[] { i.BaseVersionId, i.Loader }.Where(s => !string.IsNullOrEmpty(s)))),
            }).ToList();
        });
        await RefreshGames();
    }

    private async Task RefreshGames()
    {
        if (_app == null) return;
        try
        {
            var list = await _app.Games.ListAsync();
            _games.ItemsSource = list.Where(g => g.Running).Select(g => new ListBoxItem
            {
                Tag = g,
                Content = Row(g.VersionId, "PID " + g.Pid),
            }).ToList();
        }
        catch (PmclException)
        {
        }
    }

    private async Task LaunchSelected()
    {
        if ((_instances.SelectedItem as ListBoxItem)?.Tag is not InstanceSummary instance) return;
        await Run(async () =>
        {
            var error = await _app!.Launch.InstanceAsync(instance.Id);
            _status.Text = error ?? T($"已让 PMCL 启动「{instance.Name}」。", $"Asked PMCL to launch \"{instance.Name}\".");
        });
    }

    private async Task KillSelected()
    {
        if ((_games.SelectedItem as ListBoxItem)?.Tag is not GameProcessSummary game) return;
        await Run(async () =>
        {
            await _app!.Games.KillAsync(game.Pid);
            await RefreshGames();
        });
    }

    private void OnLauncherEvent(PmclEvent e)
    {
        var items = (_events.ItemsSource as List<string>) ?? new List<string>();
        items.Insert(0, $"{DateTime.Now:HH:mm:ss}  {e.Type}  {Short(e.Data.ToString())}");
        if (items.Count > 50) items.RemoveAt(items.Count - 1);
        _events.ItemsSource = null;
        _events.ItemsSource = items;
        if (e.Type is "GameLaunchedEvent" or "GameExitedEvent") _ = RefreshGames();
    }

    private async Task Run(Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (PmclException e)
        {
            _status.Text = e.Message;
        }
    }

    private void BringToFront()
    {
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        Topmost = true;
        Topmost = false;
    }

    private static void Quit()
    {
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop) desktop.Shutdown();
    }

    private void SetEnabled(bool on)
    {
        _launch.IsEnabled = on;
        _kill.IsEnabled = on;
        _refresh.IsEnabled = on;
        _notify.IsEnabled = on;
    }

    private static Control Section(string caption, ListBox list, params Button[] buttons)
    {
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var b in buttons) actions.Children.Add(b);
        var panel = new DockPanel();
        var top = Caption(caption);
        DockPanel.SetDock(top, Dock.Top);
        DockPanel.SetDock(actions, Dock.Bottom);
        actions.Margin = new Thickness(0, 8, 0, 0);
        panel.Children.Add(top);
        panel.Children.Add(actions);
        panel.Children.Add(new Border
        {
            CornerRadius = new CornerRadius(8),
            ClipToBounds = true,
            Margin = new Thickness(0, 6, 0, 0),
            Child = list,
        });
        return panel;
    }

    private static TextBlock Caption(string text) => new() { Text = text, FontWeight = FontWeight.SemiBold, Opacity = 0.8 };

    private static Control Row(string title, string detail) => new StackPanel
    {
        Spacing = 1,
        Children =
        {
            new TextBlock { Text = title },
            new TextBlock { Text = detail, FontSize = 11, Opacity = 0.6 },
        },
    };

    private static string Short(string text) => text.Length > 120 ? text[..120] + "…" : text;

    private static string T(string zh, string en) => Zh ? zh : en;
}
