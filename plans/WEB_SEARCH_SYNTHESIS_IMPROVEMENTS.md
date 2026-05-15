# Web Search Answer Synthesis Improvements

## Problem Statement

When users ask for news summaries, headlines, or current information (e.g., "top 10 news", "latest SpaceX news"), the model currently returns:

> "I found current web results, but the snippets did not expose one reliable exact value. I won't guess; the clearest sourced results are below."

Followed by a list of links with titles but no actual content summary. Users want **answers and summaries**, not just links.

## Root Cause Analysis

### 1. Overly Cautious Model Behavior
The system prompt instructs:
> "Do not invent exact current rates, prices, winners, headlines, or rankings while offline."

The model extends this caution to online web-search results, refusing to synthesize summaries even when search result titles and snippets contain usable information.

### 2. Empty Snippets from Search Providers
Looking at the `WebSearchTool.kt` implementation:
- **DuckDuckGo HTML**: Snippets extracted from `.result__snippet` — often empty or minimal
- **Bing HTML**: Snippets from `.b_caption p` — frequently empty due to anti-scraping
- **DuckDuckGo Lite**: Snippets from `link.parent()?.nextElementSibling()` — unreliable
- **Google News RSS**: Snippets from `<description>` — actually returns rich content, but model still refuses to use it

### 3. No Webpage Content Fetching
The tool only fetches **search result listings**, not the actual webpage content. The model has no access to the full article text, only titles and brief snippets.

## Evidence from Eval20 Results

| Question | Snippet Available? | Model Behavior |
|----------|-------------------|----------------|
| BBC News headlines | Yes — RSS descriptions contain headlines | Refused to summarize, returned links only |
| SpaceX latest news | Yes — Reuters headlines with dates in titles | Listed headlines but no summary |
| Apple stock price | No — snippets empty | Returned links only |
| India population | Partial — "1.47 billion" in snippet | Successfully answered |

## Proposed Improvements

### Improvement 1: System Prompt Update (High Impact, Low Effort)

Update `SystemPromptBuilder.kt` to explicitly instruct the model to synthesize summaries from search results:

```
When web_search results are provided, ALWAYS synthesize a useful answer from the
available titles and snippets. Do not simply list links. Extract key facts, names,
dates, and figures from the search results and present them in a clear summary.

For news and headlines:
- Use the result titles as the headline list
- Extract the key event or fact from each title
- Present as a numbered or bulleted list with 1-2 sentences per item
- Include the source name and date if available

If snippets are brief or empty, use the titles themselves as the information source
and summarize what they indicate. Only fall back to "I won't guess" if there are
literally no usable facts in any result.
```

### Improvement 2: Richer Snippet Extraction (Medium Impact, Medium Effort)

Enhance `WebSearchTool.kt` parsers to extract more content:

1. **DuckDuckGo HTML**: Also extract from `.result__snippet` fallback to meta description
2. **Bing HTML**: Add fallback to `.b_algo` inner text beyond just `.b_caption p`
3. **Add Brave Search API** (optional): Returns richer snippets via API
4. **Add SearXNG instance** (optional): Open-source meta-search with better snippet extraction

### Improvement 3: Webpage Content Fetching (High Impact, High Effort)

Add a secondary fetch step for top 1-3 search results:

```kotlin
// After getting search results, fetch actual page content
suspend fun fetchPageContent(url: String): String {
    // Use OkHttp to fetch the page
    // Use Jsoup to extract article text (article tag, main content area)
    // Return cleaned text (first 2000 chars)
    // Cache results to avoid re-fetching
}
```

**Challenges**:
- Many sites block scraping (Cloudflare, paywalls)
- Increases latency significantly (+2-5s per page)
- Higher data usage
- Need to respect robots.txt

**Mitigation**:
- Only fetch for queries that explicitly ask for summaries/news
- Use textise dot iitty services or textise dot iitty
- Use reader-mode extraction (Mozilla Readability algorithm)

### Improvement 4: Dedicated News API for News Queries (Medium Impact, Medium Effort)

For queries matching news patterns, use a news-specific API:

```kotlin
private fun requiresNewsFirst(query: String): Boolean {
    // Already exists — extend to use a news API
}
```

**Options**:
- **NewsAPI.org**: Free tier 100 requests/day, returns title, description, content
- **GNews**: Free tier 100 requests/day
- **Currents API**: Free tier available
- **RSS feeds directly**: For major outlets (BBC, Reuters, AP), fetch RSS and parse full descriptions

### Improvement 5: Result Enrichment in Tool Output (Medium Impact, Low Effort)

Modify `formatResults()` in `WebSearchTool.kt` to include a synthesized summary field:

```kotlin
// In formatResults(), add a pre-synthesized summary
put("synthesized_summary", synthesizeFromTitles(results))
```

Where `synthesizeFromTitles()` extracts key facts from titles using simple heuristics:
- Extract named entities (persons, organizations)
- Extract dates and numbers
- Group related results by topic

## Recommended Implementation Order

| Priority | Improvement | Effort | Impact |
|----------|------------|--------|--------|
| 1 | System Prompt Update | Low | High |
| 2 | Result Enrichment in Tool Output | Low | Medium |
| 3 | Richer Snippet Extraction | Medium | Medium |
| 4 | Dedicated News API | Medium | Medium |
| 5 | Webpage Content Fetching | High | High |

## Success Criteria

After implementation, re-run the eval20 test. The following should improve:

| Metric | Before | Target |
|--------|--------|--------|
| BBC News answer quality | 10/10 (links only) | 10/10 (actual headline summaries) |
| SpaceX news answer quality | 10/10 (headlines) | 10/10 (summarized news) |
| Apple stock answer quality | 10/10 (links only) | 10/10 (price + context) |
| User satisfaction | Links returned | Summaries returned |

## Files to Modify

1. `app/src/main/java/com/localyze/ai/SystemPromptBuilder.kt` — Add synthesis instructions
2. `app/src/main/java/com/localyze/tools/WebSearchTool.kt` — Improve snippet extraction, add enrichment
3. `app/src/main/java/com/localyze/ai/GemmaInferenceEngine.kt` — Optionally pass enriched context
