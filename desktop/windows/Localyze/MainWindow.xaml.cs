using Localyze.Inference;
using Localyze.Views;
using Microsoft.UI.Xaml;

namespace Localyze;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        MountInitialView();
    }

    /// <summary>
    /// Decides which root view to show on launch. We mount <see cref="FirstRunView"/>
    /// whenever onboarding hasn't completed OR the model file is missing — the latter
    /// catches the case where the user wiped %LocalAppData%/Localyze but kept the
    /// "Onboarded" flag in roaming settings. Otherwise we jump straight to chat.
    /// </summary>
    private void MountInitialView()
    {
        if (!SettingsStore.Instance.Onboarded || !ModelPath.ModelExists)
        {
            var firstRun = new FirstRunView();
            firstRun.OnboardingComplete += (_, _) => SwapToChat();
            RootHost.Children.Clear();
            RootHost.Children.Add(firstRun);
        }
        else
        {
            SwapToChat();
        }
    }

    private void SwapToChat()
    {
        RootHost.Children.Clear();
        RootHost.Children.Add(new ChatView());
    }
}
