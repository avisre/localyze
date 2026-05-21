using System;
using System.IO;
using System.Threading;
using Localyze.Download;
using Localyze.Hardware;
using Localyze.Inference;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Localyze.Views;

/// <summary>
/// Three-step onboarding: probe hardware → download (or import) the Gemma 4 E4B
/// ONNX model → confirm and hand off to <see cref="ChatView"/>. Sets
/// <see cref="SettingsStore.Onboarded"/> on completion so the main window skips
/// this view on subsequent launches.
/// </summary>
public sealed partial class FirstRunView : UserControl
{
    private HardwareReport _hw;
    private Selection _selection;
    private CancellationTokenSource? _downloadCts;
    private string _modelDownloadUrl = string.Empty;
    private string _modelSha256 = string.Empty;
    private long _modelSizeBytes;

    public event EventHandler? OnboardingComplete;

    public FirstRunView()
    {
        InitializeComponent();
        Loaded += async (_, _) => await RunHardwareProbeAsync();
    }

    // ───────────────────────── Step 1: hardware probe ─────────────────────────

    private async System.Threading.Tasks.Task RunHardwareProbeAsync()
    {
        ProbeSpinner.IsActive = true;
        ProbeStatus.Text = "Detecting your hardware…";
        Step1NextButton.IsEnabled = false;

        try
        {
            // HardwareProbe.Run() blocks on WMI; push to a worker thread so the UI stays live.
            _hw = await System.Threading.Tasks.Task.Run(HardwareProbe.Run);
            _selection = BackendSelector.Pick(_hw);

            ProbeSpinner.IsActive = false;
            ProbeStatus.Text = "Detected:";
            ProbeReport.Text =
                $"CPU : {_hw.CpuName} ({_hw.CpuCores} cores, AVX2={_hw.HasAvx2}, AVX-512={_hw.HasAvx512})\n" +
                $"RAM : {_hw.RamGb} GB\n" +
                $"GPU : {_hw.GpuVendor} {_hw.GpuName} ({_hw.VramGb} GB VRAM)\n" +
                $"NPU : {_hw.Npu}\n" +
                $"\n" +
                $"Backend : {_selection.Tier} via {_selection.Ep}\n" +
                $"Tier    : {_selection.TierId}  ({_selection.Quantization})\n" +
                $"Reason  : {_selection.Reason}";
            Step1NextButton.IsEnabled = true;
        }
        catch (UnsupportedHardwareException ex)
        {
            ProbeSpinner.IsActive = false;
            ProbeStatus.Text = "This device can't run Localyze.ai";
            ProbeError.Text = ex.Message;
            ProbeError.Visibility = Visibility.Visible;
            // Refuse on unsupported hardware (mobile parity): no Continue button.
        }
        catch (Exception ex)
        {
            ProbeSpinner.IsActive = false;
            ProbeStatus.Text = "Hardware detection failed";
            ProbeError.Text = ex.Message;
            ProbeError.Visibility = Visibility.Visible;
        }
    }

    private void OnStep1Next(object _, RoutedEventArgs __)
    {
        // Resolve the tier's download URL / sha256 from the shared manifest later;
        // for now we wire the selection's TierId into the downloader so it picks the
        // right ONNX bundle. The URL / sha values are resolved inside ModelDownloader.
        _modelDownloadUrl = $"https://cdn.localyze.app/desktop/{_selection.TierId}/gemma-4-e4b.onnx";
        _modelSha256 = ""; // resolved from manifest at download time
        _modelSizeBytes = 0;

        Step1Panel.Visibility = Visibility.Collapsed;
        Step2Panel.Visibility = Visibility.Visible;
        StepLabel.Text = "Step 2 of 3 — Download the model";

        // If the model is already on disk, skip straight to step 3.
        if (ModelPath.ModelExists)
        {
            DownloadStatus.Text = "Model already present on disk.";
            DownloadProgress.Value = 1;
            Step2NextButton.IsEnabled = true;
            StartDownloadButton.IsEnabled = false;
        }
    }

    // ───────────────────────── Step 2: model download ─────────────────────────

    private async void OnStartDownload(object _, RoutedEventArgs __)
    {
        StartDownloadButton.IsEnabled = false;
        BrowseLocalButton.IsEnabled = false;
        DownloadStatus.Text = "Resolving manifest…";
        _downloadCts = new CancellationTokenSource();

        try
        {
            ModelPath.EnsureDirectories();
            var dl = new ModelDownloader(ModelPath.ModelDir);
            var tier = await dl.ResolveTierAsync(_selection.TierId, _downloadCts.Token);

            await foreach (var p in dl.FetchAsync(tier, _downloadCts.Token))
            {
                double frac = p.Total > 0 ? (double)p.Done / p.Total : 0;
                DownloadProgress.Value = frac;
                DownloadStatus.Text = $"{p.Artifact}: {Bytes(p.Done)} / {Bytes(p.Total)} ({frac:P0})";
            }

            DownloadStatus.Text = "Download complete and verified.";
            Step2NextButton.IsEnabled = true;
        }
        catch (OperationCanceledException)
        {
            DownloadStatus.Text = "Download canceled.";
            StartDownloadButton.IsEnabled = true;
            BrowseLocalButton.IsEnabled = true;
        }
        catch (Exception ex)
        {
            DownloadStatus.Text = $"Download failed: {ex.Message}";
            StartDownloadButton.IsEnabled = true;
            BrowseLocalButton.IsEnabled = true;
        }
    }

    private async void OnBrowseLocal(object _, RoutedEventArgs __)
    {
        // FileOpenPicker in WinUI 3 needs an HWND to know which window to attach to.
        var picker = new FileOpenPicker();
        var hwnd = WindowNative.GetWindowHandle(App.MainWindowInstance);
        InitializeWithWindow.Initialize(picker, hwnd);
        picker.FileTypeFilter.Add(".onnx");
        picker.FileTypeFilter.Add(".zip");
        picker.FileTypeFilter.Add("*");

        var file = await picker.PickSingleFileAsync();
        if (file is null) return;

        try
        {
            ModelPath.EnsureDirectories();
            var dest = Path.Combine(ModelPath.ModelDir, Path.GetFileName(file.Path));
            await System.Threading.Tasks.Task.Run(() => File.Copy(file.Path, dest, overwrite: true));
            DownloadStatus.Text = $"Imported {Path.GetFileName(file.Path)}.";
            DownloadProgress.Value = 1;
            Step2NextButton.IsEnabled = true;
            StartDownloadButton.IsEnabled = false;
        }
        catch (Exception ex)
        {
            DownloadStatus.Text = $"Import failed: {ex.Message}";
        }
    }

    private void OnStep2Next(object _, RoutedEventArgs __)
    {
        Step2Panel.Visibility = Visibility.Collapsed;
        Step3Panel.Visibility = Visibility.Visible;
        StepLabel.Text = "Step 3 of 3 — All set";
    }

    // ───────────────────────── Step 3: hand off ─────────────────────────

    private void OnStartChat(object _, RoutedEventArgs __)
    {
        SettingsStore.Instance.Onboarded = true;
        OnboardingComplete?.Invoke(this, EventArgs.Empty);
    }

    private static string Bytes(long n)
    {
        if (n >= 1L << 30) return $"{n / (double)(1L << 30):F2} GB";
        if (n >= 1L << 20) return $"{n / (double)(1L << 20):F1} MB";
        if (n >= 1L << 10) return $"{n / (double)(1L << 10):F1} KB";
        return $"{n} B";
    }
}
