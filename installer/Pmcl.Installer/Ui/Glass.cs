using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;

namespace Pmcl.Installer.Ui;

/// <summary>PMCL 主题色板：和启动器 Theme.kt 的默认浅色方案保持一致。</summary>
internal static class Tokens
{
    public static readonly Color Ink = Color.Parse("#1A1D23");
    public static readonly Color InkSoft = Color.Parse("#4A505B");
    public static readonly Color InkMuted = Color.Parse("#74777F");
    public static readonly Color InkFaint = Color.Parse("#9BA0A8");

    public static readonly Color Primary = Color.Parse("#3D8BFF");
    public static readonly Color PrimaryHover = Color.Parse("#2F78E6");
    public static readonly Color PrimaryPressed = Color.Parse("#2268CC");
    public static readonly Color Secondary = Color.Parse("#55C57A");
    public static readonly Color Error = Color.Parse("#B3261E");

    public static readonly IBrush PrimaryBrush = new SolidColorBrush(Primary);
    public static readonly IBrush Surface = new SolidColorBrush(Colors.White);
    public static readonly IBrush SurfaceVariant = new SolidColorBrush(Color.Parse("#E2E5EA"));
    public static readonly IBrush Track = new SolidColorBrush(Color.Parse("#E2E5EA"));

    /// <summary>PMCL 玻璃卡片：surface 55% 透明度，靠窗口毛玻璃分层，不加阴影。</summary>
    public static readonly IBrush Card = new SolidColorBrush(Color.FromArgb(0x8C, 0xFF, 0xFF, 0xFF));
    public static readonly IBrush CardEdge = new SolidColorBrush(Color.FromArgb(0x66, 0xFF, 0xFF, 0xFF));

    /// <summary>PMCL 默认 background #F5F7FA，留出一点透光给系统毛玻璃。</summary>
    public static readonly IBrush WindowWash = new SolidColorBrush(Color.FromArgb(0xD9, 0xF5, 0xF7, 0xFA));

    public static TextBlock Text(string text, double size, Color color, FontWeight weight = FontWeight.Normal) =>
        new()
        {
            Text = text,
            FontSize = size,
            Foreground = new SolidColorBrush(color),
            FontWeight = weight,
            VerticalAlignment = VerticalAlignment.Center
        };
}

internal enum StepState
{
    Pending,
    Active,
    Done,
    Failed
}

/// <summary>圆角 8dp 的扁平按钮：主按钮实心蓝，次按钮浅灰底。无渐变、无阴影。</summary>
internal sealed class FlatButton : Border
{
    private readonly TextBlock _label;
    private readonly bool _primary;
    private bool _enabled = true;
    private bool _hover;
    private bool _pressed;

    public event EventHandler? Click;

    public FlatButton(string text, bool primary)
    {
        _primary = primary;
        _label = Tokens.Text(text, 13, primary ? Colors.White : Tokens.InkSoft, FontWeight.SemiBold);
        _label.HorizontalAlignment = HorizontalAlignment.Center;

        CornerRadius = new CornerRadius(8);
        BorderThickness = new Thickness(1);
        Padding = new Thickness(16, 8);
        MinWidth = 92;
        MinHeight = 36;
        Child = _label;
        Cursor = new Cursor(StandardCursorType.Hand);
        Transitions = new Transitions
        {
            new BrushTransition { Property = BackgroundProperty, Duration = TimeSpan.FromMilliseconds(150), Easing = new LinearEasing() },
            new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(150), Easing = new LinearEasing() }
        };

        Apply();
        PointerEntered += (_, _) => { _hover = true; Apply(); };
        PointerExited += (_, _) => { _hover = false; _pressed = false; Apply(); };
        PointerPressed += (_, e) =>
        {
            if (!_enabled || !e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
            _pressed = true;
            Apply();
            e.Handled = true;
        };
        PointerReleased += (_, _) =>
        {
            var fire = _enabled && _pressed;
            _pressed = false;
            Apply();
            if (fire) Click?.Invoke(this, EventArgs.Empty);
        };
    }

    public string Text
    {
        get => _label.Text ?? "";
        set => _label.Text = value;
    }

    public void SetEnabled(bool enabled)
    {
        _enabled = enabled;
        IsHitTestVisible = enabled;
        Opacity = enabled ? 1 : 0.48;
        Cursor = new Cursor(enabled ? StandardCursorType.Hand : StandardCursorType.Arrow);
        _pressed = false;
        Apply();
    }

    private void Apply()
    {
        if (_primary)
        {
            Background = new SolidColorBrush(_pressed ? Tokens.PrimaryPressed : _hover ? Tokens.PrimaryHover : Tokens.Primary);
            BorderBrush = Brushes.Transparent;
        }
        else
        {
            Background = new SolidColorBrush(Color.FromArgb((byte)(_pressed ? 0xFF : _hover ? 0xCC : 0x99), 0xE2, 0xE5, 0xEA));
            BorderBrush = Brushes.Transparent;
        }
    }
}

/// <summary>步骤标记：一个小圆点加文字，不做胶囊底色。</summary>
internal sealed class StepMark : Border
{
    private readonly Ellipse _dot = new() { Width = 6, Height = 6, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _label;

    public StepMark(string text)
    {
        _label = Tokens.Text(text, 11.5, Tokens.InkFaint);
        Child = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 7,
            Children = { _dot, _label }
        };
        Set(StepState.Pending);
    }

    public void Set(StepState state)
    {
        var (dot, ink) = state switch
        {
            StepState.Active => (Tokens.Primary, Tokens.Primary),
            StepState.Done => (Tokens.Secondary, Tokens.InkSoft),
            StepState.Failed => (Tokens.Error, Tokens.Error),
            _ => (Color.Parse("#BFC4CC"), Tokens.InkFaint)
        };
        _dot.Fill = new SolidColorBrush(dot);
        _label.Foreground = new SolidColorBrush(ink);
    }
}

/// <summary>进度条：轨道和指示条都用主题色，圆角 3dp。</summary>
internal sealed class Meter : ProgressBar
{
    public Meter()
    {
        Minimum = 0;
        Maximum = 100;
        Height = 6;
        CornerRadius = new CornerRadius(3);
        Background = Tokens.Track;
        Foreground = Tokens.PrimaryBrush;
    }

    protected override Type StyleKeyOverride => typeof(ProgressBar);
}
