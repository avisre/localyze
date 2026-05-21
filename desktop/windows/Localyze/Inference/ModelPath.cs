using System.IO;

namespace Localyze.Inference;

/// Single source of truth for on-disk paths. Used by Download/ + Inference/.
public static class ModelPath
{
    public static string AppDataRoot { get; } = Path.Combine(
        System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData),
        "Localyze");

    public static string ModelDir { get; } = Path.Combine(AppDataRoot, "models", "gemma-4-e4b-it-onnx");

    public static string ArtifactsDir { get; } = Path.Combine(
        System.Environment.GetFolderPath(System.Environment.SpecialFolder.UserProfile),
        "Localyze", "artifacts");

    public static void EnsureDirectories()
    {
        Directory.CreateDirectory(AppDataRoot);
        Directory.CreateDirectory(ModelDir);
        Directory.CreateDirectory(ArtifactsDir);
    }

    public static bool ModelExists =>
        File.Exists(Path.Combine(ModelDir, "genai_config.json"));
}
