using Localyze.Hardware;
using Localyze.Inference;
using Localyze.Research;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Localyze.Views;

public sealed partial class ChatView : UserControl
{
    private OnnxBackend? _backend;
    private ReActAgent? _agent;
    private TextBlock? _activeStreamBlock;

    public ChatView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        WebSearchToggle.IsOn = SettingsStore.Instance.WebSearchEnabled;
        WebSearchToggle.Toggled += (_, _) =>
            SettingsStore.Instance.WebSearchEnabled = WebSearchToggle.IsOn;
        CodeExecToggle.IsOn = SettingsStore.Instance.CodeExecEnabled;
        CodeExecToggle.Toggled += (_, _) =>
            SettingsStore.Instance.CodeExecEnabled = CodeExecToggle.IsOn;
    }

    private async void OnLoaded(object _, RoutedEventArgs __)
    {
        var hw = HardwareProbe.Run();
        try
        {
            var sel = BackendSelector.Pick(hw);
            ModelPath.EnsureDirectories();

            if (!ModelPath.ModelExists)
            {
                StatusBar.Text = $"Gemma 4 E4B ({sel.Quantization}) on {sel.Tier} — model not yet downloaded.";
                return;
            }

            StatusBar.Text = $"Gemma 4 E4B ({sel.Quantization}) on {sel.Tier} via {sel.Ep} — loading…";
            _backend = await System.Threading.Tasks.Task.Run(
                () => new OnnxBackend(ModelPath.ModelDir, sel.Ep));
            _agent = new ReActAgent(_backend);
            StatusBar.Text = $"Gemma 4 E4B ({sel.Quantization}) on {sel.Tier} via {sel.Ep} — ready.";
        }
        catch (UnsupportedHardwareException ex)
        {
            StatusBar.Text = "Unsupported hardware — " + ex.Message;
        }
        catch (System.Exception ex)
        {
            StatusBar.Text = "Init error — " + ex.Message;
        }
    }

    private async void OnSend(object _, RoutedEventArgs __)
    {
        var text = InputBox.Text?.Trim();
        if (string.IsNullOrEmpty(text) || _backend is null) return;
        InputBox.Text = "";
        SendButton.IsEnabled = false;

        MessagesPanel.Children.Add(new TextBlock { Text = "You: " + text, TextWrapping = TextWrapping.Wrap });
        _activeStreamBlock = new TextBlock { Text = "Localyze: ", TextWrapping = TextWrapping.Wrap };
        MessagesPanel.Children.Add(_activeStreamBlock);
        MessagesScroll.ChangeView(null, double.MaxValue, null);

        try
        {
            await foreach (var piece in _backend.GenerateAsync(text, SystemPrompt.Chat()))
            {
                _activeStreamBlock.Text += piece;
                MessagesScroll.ChangeView(null, double.MaxValue, null);
            }
        }
        catch (System.Exception ex)
        {
            _activeStreamBlock.Text += $"\n[error: {ex.Message}]";
        }
        finally
        {
            SendButton.IsEnabled = true;
        }
    }
}
