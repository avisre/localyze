# `<viz>` Block Format

The model emits these inline in its output. Each native platform has a parser + renderer in `Artifacts/` that turns them into OS-native widgets.

Format is XML-ish for streamability (a partial block can be parsed and rendered live while the model keeps writing).

## Common attributes

- `type` (required) — see table below
- `title` (optional) — caption shown above the artifact
- `id` (optional) — stable id for live updates; later blocks with the same id replace the earlier one
- `save` (optional, default `true`) — auto-save to `~/Localyze/artifacts/`

## Types

| `type` | Required attrs | Notes |
|---|---|---|
| `chart` | `kind` (line / bar / scatter / pie / area / heatmap), `data`, `x`, `y` | Streams point-by-point as data arrives |
| `table` | `data` | Optional `editable="true"`, `sortable="true"` (default true), `columns="..."` |
| `map`   | `markers` (array of `{lat, lng, label}`) | Optional `center`, `zoom`. Offline tiles cached. |
| `run`   | `lang` (python / js / shell), inner code text | Executed locally. Stdout/exit code rendered. Outputs auto-feed next viz block. |
| `code`  | `lang`, inner code text | Syntax-highlighted, copyable, runnable via "Run" button |
| `form`  | `schema` (JSON Schema) | Renders real OS controls. On submit, posts back as a tool result. |
| `image` | `src` (local path or data URL) | Native zoom/pan |
| `pdf`   | `data` (base64 PDF) | Renders inline + Save/Print buttons |

## Streaming protocol

The renderer accepts partial JSON in `data="..."`. As tokens arrive:

```
<viz type="chart" kind="line" data='[{"x":1,"y":2},
```
…the renderer waits for valid JSON, then …
```
<viz type="chart" kind="line" data='[{"x":1,"y":2},{"x":2,"y":3}
```
…parses what it can, draws the first two points, and keeps appending.

Closing tag `</viz>` finalizes the artifact and triggers auto-save.

## Why this beats Claude's artifacts

Claude renders artifacts in a sandboxed iframe (web tech only, slower, no real OS access). Localyze renders with native widgets:

- **Editable tables** save back to CSV/XLSX in one click via the OS file dialog
- **`run` blocks** execute on the host (Python via embedded interpreter, JS via Node), and output flows into the next viz block automatically
- **Maps** use Apple MapKit / Bing Maps / QtLocation with offline tiles cached locally
- **Charts** are GPU-rendered at 60 fps by the native chart framework
- **Export** to PDF/PNG/CSV/XLSX is one button, using the OS's print/share sheet
