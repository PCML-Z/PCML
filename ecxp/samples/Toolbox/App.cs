using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using Pmcl.Ecxp;

namespace Pmcl.Toolbox;

/// <summary>跟着 PMCL 的深浅色和强调色走。</summary>
public sealed class App : Application
{
    public override void Initialize()
    {
        var theme = new FluentTheme();
        if (PmclApp.Accent is { } hex && Color.TryParse(hex, out var accent))
        {
            theme.Palettes[ThemeVariant.Dark] = new ColorPaletteResources { Accent = accent };
            theme.Palettes[ThemeVariant.Light] = new ColorPaletteResources { Accent = accent };
        }
        Styles.Add(theme);
        RequestedThemeVariant = PmclApp.Theme == "light" ? ThemeVariant.Light : ThemeVariant.Dark;
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = new MainWindow();
        }
        base.OnFrameworkInitializationCompleted();
    }
}
