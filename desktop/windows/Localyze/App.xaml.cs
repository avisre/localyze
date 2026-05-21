using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;

namespace Localyze;

public partial class App : Application
{
    private Window? _window;

    /// Exposed so views (e.g. FirstRunView's FileOpenPicker) can attach to the main HWND.
    public static Window MainWindowInstance { get; private set; } = null!;

    // %LocalAppData%/Localyze/crashes — one .log file per fatal exception.
    private static readonly string CrashDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Localyze", "crashes");

    public App()
    {
        InitializeComponent();

        // 1) Non-UI thread exceptions (background tasks, finalizers, etc.). These are
        //    almost always fatal — the CLR is going to terminate the process after we
        //    return — so the only useful thing we can do is write the crash log first.
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            var path = WriteCrash(e.ExceptionObject as Exception, "AppDomain.UnhandledException");
            TryShowCrashDialog(path);
        };

        // 2) Faulted Tasks that nobody awaited. Marking observed prevents the CLR's
        //    "unobserved task exception" policy from tearing down the process on GC.
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            var path = WriteCrash(e.Exception, "TaskScheduler.UnobservedTaskException");
            e.SetObserved();
            TryShowCrashDialog(path);
        };

        // 3) WinUI's own UI-thread unhandled-exception hook. Setting Handled = true
        //    lets the app keep running where the framework considers it safe.
        this.UnhandledException += (_, e) =>
        {
            var path = WriteCrash(e.Exception, "Application.UnhandledException");
            e.Handled = true;
            TryShowCrashDialog(path);
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        MainWindowInstance = _window;
        _window.Activate();
    }

    // Writes the failure to a timestamped .log under %LocalAppData%/Localyze/crashes/.
    // We intentionally never throw out of this method — a crash logger that crashes is
    // worse than a silent one. Returns the path written (or null on best-effort failure).
    private static string? WriteCrash(Exception? ex, string source)
    {
        try
        {
            Directory.CreateDirectory(CrashDir);
            var stamp = DateTime.UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'");
            var path = Path.Combine(CrashDir, $"{stamp}.log");
            var sb = new StringBuilder();
            sb.AppendLine($"timestamp_utc : {DateTime.UtcNow:O}");
            sb.AppendLine($"source        : {source}");
            sb.AppendLine($"os            : {Environment.OSVersion}");
            sb.AppendLine($"clr           : {Environment.Version}");
            sb.AppendLine($"app_version   : {typeof(App).Assembly.GetName().Version}");
            sb.AppendLine($"working_dir   : {Environment.CurrentDirectory}");
            sb.AppendLine("---");
            sb.AppendLine(ex?.ToString() ?? "<null exception>");
            File.WriteAllText(path, sb.ToString());
            return path;
        }
        catch
        {
            return null;
        }
    }

    // Best-effort ContentDialog notifying the user that a crash log was saved.
    // Offers a single button to copy the path to the clipboard — we deliberately
    // do not auto-send anything anywhere; this is a local-only diagnostic.
    private void TryShowCrashDialog(string? path)
    {
        if (path is null || _window?.Content is not FrameworkElement root) return;
        try
        {
            // Marshal onto the UI thread; the hooks above can fire from worker threads.
            root.DispatcherQueue.TryEnqueue(async () =>
            {
                try
                {
                    var dialog = new ContentDialog
                    {
                        Title = "Localyze hit an error",
                        Content = $"A crash log was saved to:\n\n{path}\n\nNothing was sent anywhere.",
                        PrimaryButtonText = "Copy path",
                        CloseButtonText = "Close",
                        DefaultButton = ContentDialogButton.Close,
                        XamlRoot = root.XamlRoot
                    };
                    var result = await dialog.ShowAsync();
                    if (result == ContentDialogResult.Primary)
                    {
                        var pkg = new DataPackage();
                        pkg.SetText(path);
                        Clipboard.SetContent(pkg);
                    }
                }
                catch
                {
                    // If even the dialog fails (e.g. window already torn down), the log on
                    // disk is the user-visible artifact — that's enough.
                }
            });
        }
        catch
        {
            // Same rationale as WriteCrash: never throw out of the crash path.
        }
    }
}
