using System.IO;
using System.Text.Json.Nodes;
using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage;

namespace Localyze.Artifacts;

public sealed partial class ArtifactView : UserControl
{
    private VizBlock? _block;

    public ArtifactView() => InitializeComponent();

    public VizBlock? Block
    {
        get => _block;
        set { _block = value; Render(); }
    }

    private void Render()
    {
        if (_block is null) return;
        TitleText.Text = _block.Attrs.GetValueOrDefault("title") ?? _block.Kind.ToString();
        BodyHost.Content = _block.Kind switch
        {
            VizKind.Chart => BuildChart(_block),
            VizKind.Table => BuildTable(_block),
            VizKind.Code  => BuildCode(_block),
            VizKind.Run   => BuildRun(_block),
            VizKind.Image => BuildImage(_block),
            _ => new TextBlock { Text = $"Unsupported viz type: {_block.Kind}", Opacity = 0.7 }
        };
    }

    private static UIElement BuildChart(VizBlock b)
    {
        // Native chart: CommunityToolkit.Labs.WinUI.LineChart or just a custom Canvas.
        // Simplest viable: render as a DataGrid summary plus a textblock with the data
        // shape — the polished chart implementation comes in the next pass.
        var dataJson = b.Attrs.GetValueOrDefault("data") ?? "[]";
        return new TextBlock
        {
            Text = $"Chart ({b.Attrs.GetValueOrDefault("kind") ?? "line"}) — {dataJson.Length} chars of data\n(native chart renderer wired in next pass)",
            TextWrapping = TextWrapping.Wrap,
            FontFamily = new FontFamily("Consolas"),
        };
    }

    private static UIElement BuildTable(VizBlock b)
    {
        var grid = new DataGrid
        {
            AutoGenerateColumns = true,
            IsReadOnly = b.Attrs.GetValueOrDefault("editable") != "true",
            MinHeight = 160,
        };
        var dataJson = b.Attrs.GetValueOrDefault("data") ?? "[]";
        try
        {
            var rows = JsonNode.Parse(dataJson)?.AsArray();
            if (rows is null) return grid;
            // Materialize to a list of expando-like dictionaries so DataGrid auto-binds.
            var bindable = new System.Collections.ObjectModel.ObservableCollection<System.Dynamic.ExpandoObject>();
            foreach (var item in rows)
            {
                if (item is not JsonObject row) continue;
                var exp = new System.Dynamic.ExpandoObject() as System.Collections.Generic.IDictionary<string, object?>;
                foreach (var (k, v) in row) exp[k] = v?.GetValue<string>() ?? v?.ToString();
                bindable.Add((System.Dynamic.ExpandoObject)exp);
            }
            grid.ItemsSource = bindable;
        }
        catch { }
        return grid;
    }

    private static UIElement BuildCode(VizBlock b)
    {
        return new TextBox
        {
            Text = b.Inner ?? "",
            IsReadOnly = true,
            AcceptsReturn = true,
            FontFamily = new FontFamily("Consolas"),
            MinHeight = 120,
        };
    }

    private static UIElement BuildRun(VizBlock b)
    {
        var sp = new StackPanel { Spacing = 4 };
        sp.Children.Add(new TextBlock { Text = $"Code ({b.Attrs.GetValueOrDefault("lang") ?? ""})", Opacity = 0.7 });
        sp.Children.Add(BuildCode(b));
        sp.Children.Add(new TextBlock { Text = "Output", Opacity = 0.7 });
        sp.Children.Add(new TextBox
        {
            Text = b.Attrs.GetValueOrDefault("stdout") ?? "(run by agent — output rendered when ready)",
            IsReadOnly = true, AcceptsReturn = true,
            FontFamily = new FontFamily("Consolas"),
            MinHeight = 80,
        });
        return sp;
    }

    private static UIElement BuildImage(VizBlock b)
    {
        var src = b.Attrs.GetValueOrDefault("src");
        if (string.IsNullOrEmpty(src)) return new TextBlock { Text = "(no src)" };
        return new Image
        {
            Source = new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(new System.Uri(src)),
            Stretch = Stretch.Uniform,
            MaxHeight = 360,
        };
    }

    private async void OnSave(object _, RoutedEventArgs __)
    {
        if (_block is null) return;
        var dir = Inference.ModelPath.ArtifactsDir;
        Directory.CreateDirectory(dir);
        var data = _block.Attrs.GetValueOrDefault("data") ?? _block.Inner ?? "";
        var path = Path.Combine(dir, $"artifact-{System.DateTimeOffset.Now.ToUnixTimeSeconds()}.txt");
        await File.WriteAllTextAsync(path, data);
    }

    private void OnPdf(object _, RoutedEventArgs __)
    {
        // Real PDF export goes via PrintManager — wired in a follow-up pass.
        // Documented in STATUS.md.
    }
}
