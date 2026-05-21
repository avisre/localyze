using System.Runtime.CompilerServices;
using Microsoft.ML.OnnxRuntimeGenAI;

namespace Localyze.Inference;

/// <summary>
/// Real Gemma 4 E4B inference via ONNX Runtime GenAI. The model directory must
/// contain genai_config.json + the ONNX weights + tokenizer files. The Execution
/// Provider (DirectML / CUDA / CPU) is selected by the genai_config that ships
/// with the model artifact for the matching tier.
/// </summary>
public sealed class OnnxBackend : System.IDisposable
{
    private readonly Model _model;
    private readonly Tokenizer _tokenizer;
    private readonly TokenizerStream _stream;
    public ExecutionProvider Provider { get; }

    public OnnxBackend(string modelDirectory, ExecutionProvider ep)
    {
        Provider = ep;
        _model = new Model(modelDirectory);
        _tokenizer = new Tokenizer(_model);
        _stream = _tokenizer.CreateStream();
    }

    public IAsyncEnumerable<string> GenerateAsync(
        string prompt,
        int maxTokens = 1024,
        System.Threading.CancellationToken ct = default)
        => GenerateAsync(prompt, system: null, maxTokens, ct);

    public async IAsyncEnumerable<string> GenerateAsync(
        string prompt,
        string? system,
        int maxTokens = 1024,
        [EnumeratorCancellation] System.Threading.CancellationToken ct = default)
    {
        var templated = PromptTemplate.Gemma(prompt, system);
        var inputTokens = _tokenizer.Encode(templated);

        using var generatorParams = new GeneratorParams(_model);
        generatorParams.SetSearchOption("max_length", maxTokens);
        generatorParams.SetSearchOption("temperature", 0.7);
        generatorParams.SetSearchOption("top_p", 0.95);
        generatorParams.SetSearchOption("top_k", 40);
        generatorParams.SetSearchOption("repetition_penalty", 1.1);
        generatorParams.SetInputSequences(inputTokens);

        using var generator = new Generator(_model, generatorParams);

        while (!generator.IsDone() && !ct.IsCancellationRequested)
        {
            generator.ComputeLogits();
            generator.GenerateNextToken();
            var newTokens = generator.GetSequence(0);
            var newest = newTokens[^1];
            var piece = _stream.Decode(newest);
            if (!string.IsNullOrEmpty(piece)) yield return piece;
            await System.Threading.Tasks.Task.Yield();
        }
    }

    public void Dispose()
    {
        _stream.Dispose();
        _tokenizer.Dispose();
        _model.Dispose();
    }
}

public static class PromptTemplate
{
    /// Gemma 3n chat template. Same shape used on mobile + Mac + Linux.
    public static string Gemma(string user, string? system = null)
    {
        var s = "";
        if (!string.IsNullOrEmpty(system))
            s += $"<start_of_turn>user\n{system}\n\n{user}<end_of_turn>\n";
        else
            s += $"<start_of_turn>user\n{user}<end_of_turn>\n";
        s += "<start_of_turn>model\n";
        return s;
    }
}
