# Localyze Knowledge Answer Test - Summary

## Findings

### Issue
The model response is not being captured correctly via uiautomator XML dumps. The Compose UI renders text dynamically and it's not exposed as standard `text="..."` or `content-desc="..."` attributes.

### What was tested
1. 15 knowledge questions (no web search) to test the model's on-device responses
2. Multiple evaluation scripts using uiautomator dumps to capture responses
3. Various filter lists to exclude UI elements from the response capture

### Results
- All XML dumps show Settings/Backup screen content, not the Chat screen with the model response
- UI elements captured: "Backup passphrase", "Export", "Import", "Copy", "Share", "Backup text", "Backups"
- The Compose UI text (model responses) is not accessible via standard uiautomator attributes

### Root Cause
The `androidx.compose.ui.platform.ComposeView` renders text dynamically within View elements, and this text is not exposed as standard XML attributes that can be extracted via grep/sed. The model response is rendered but not accessible through the current XML dump approach.

### Existing eval30 Results
The existing eval30 results (e.g., eval30_20260430_054126.json) show proper responses like:
- "The... capital... of... France... is... **...Paris...**...."
- "2... plus... ...2... equals... **...4...**...."

However, running the same evaluation script now captures Settings content instead of Chat responses, suggesting there may be a difference in app state or the Compose UI rendering approach has changed.

## Recommendations

1. **Investigate Compose UI text capture**: The app uses Compose UI which may require a different approach to capture text content. Consider using a different UI testing framework or direct logcat analysis.

2. **Check app state**: Ensure the app is in the Chat screen (not Settings) before capturing responses. The current intent handling may not be opening the correct screen.

3. **Consider alternative capture methods**: 
   - Direct logcat analysis with specific filters for model output
   - Screenshot analysis with OCR
   - Direct database access if responses are stored locally

4. **Verify app version**: The eval30 script may have been working with a different version of the app. Ensure the correct version is being tested.

## Files Created
- `test_knowledge_answers.py` - Test script for 15 knowledge questions
- `eval30_v2.sh` - Updated eval script with expanded UI element filters
- `knowledge_test_results.json` - Results from test runs

## Test Results (Current)
| Question | Response | Status |
|----------|----------|--------|
| What is the capital of France? | Share | FAIL |
| What is 2 plus 2? | Share | FAIL |
| Who wrote Romeo and Juliet? | Share | FAIL |

All responses showing UI elements instead of model output.

## Next Steps
1. Analyze Compose UI rendering to find alternative text capture method
2. Check if model output is logged to logcat with specific tags
3. Verify app state and screen transitions when questions are sent