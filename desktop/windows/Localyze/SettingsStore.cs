using Windows.Storage;

namespace Localyze;

/// User settings, persisted in ApplicationData.LocalSettings. No external deps.
public sealed class SettingsStore
{
    public static SettingsStore Instance { get; } = new();

    private readonly ApplicationDataContainer _settings = ApplicationData.Current.LocalSettings;

    private const string KeyWebSearch = "webSearchEnabled";
    private const string KeySearxng   = "searxngUrl";
    private const string KeyCodeExec  = "codeExecEnabled";
    private const string KeyOnboarded = "onboarded";

    public bool WebSearchEnabled
    {
        get => _settings.Values[KeyWebSearch] is bool b && b;
        set => _settings.Values[KeyWebSearch] = value;
    }

    public string SearxngUrl
    {
        get => _settings.Values[KeySearxng] as string ?? "https://searx.be/search";
        set => _settings.Values[KeySearxng] = value;
    }

    /// Whether the `run` tool may execute model-generated python/javascript.
    /// Default false — privacy- and safety-first. Toggle in Settings.
    public bool CodeExecEnabled
    {
        get => _settings.Values[KeyCodeExec] is bool b && b;
        set => _settings.Values[KeyCodeExec] = value;
    }

    /// True once the user has completed the first-run flow (hardware probe + model
    /// fetch). MainWindow uses this to decide whether to mount FirstRunView or
    /// ChatView at launch. Defaults to false so a fresh install always onboards.
    public bool Onboarded
    {
        get => _settings.Values[KeyOnboarded] is bool b && b;
        set => _settings.Values[KeyOnboarded] = value;
    }
}
