# AggregatorX (Android)

Kotlin / Jetpack Compose / Hilt Android app imported from GitHub. Built with
Gradle. Replit's environment hosts the source and JDK 17 only — actual APK
builds happen in GitHub Actions.

## Environment

- JDK 17 installed via Nix (`installSystemDependencies(["jdk17"])`).
- No Android SDK installed locally — `assembleDebug` is run in CI.

## Architecture changes vs upstream

### Per-provider pagination (Req 2)
- `ProviderEntity` now stores `currentPage`, `pageSize`, `nextPageUrl`.
- Room DB bumped to v2 with `MIGRATION_1_2` registered in `DatabaseModule`.
- `ScrapingEngine.scrape()` returns `ScrapeResult(items, discoveredNextPageUrl)`
  and dispatches URL building on `PaginationType` (PAGE_NUMBER, OFFSET, URL_TOKEN).
- `AggregatorRepository.loadMore(name, PageDirection.{REFRESH|FORWARD|BACK})`
  rewrites only the affected provider's slice, gated by a per-provider `Mutex`.
- `SearchViewModel` exposes `navigateProviderPage(name, isForward)` and
  `refreshProvider(name)` which translate to `PageDirection`s. State is
  preserved across config changes / process death via `SavedStateHandle`
  (KEY_QUERY, KEY_RESULTS, KEY_PAGES) (Req 1).

### Headless WebView scraper (Req 4)
- `HeadlessBrowserHelper` exposes a `AggregatorBridge` JS interface with three
  callbacks: `onHtml`, `onVideoSources`, `onTokens`. One injected JS payload
  fires all three after `onPageFinished`.
- Per-navigation User-Agent rotation via `EngineUtils.getRandomUserAgent()`.
- 2–8 s human-like delay before every navigation.
- `NetworkModule` wires `ProxyVPNEngine` into the global OkHttpClient
  (proxy + UA rotation + 250–1500 ms jitter).

### Media3 ExoPlayer + downloads (Req 3)
- `VideoPlayerActivity` rewritten as `ComponentActivity` + Compose
  `AndroidView<PlayerView>` (no AppCompat dep, no `ActivityVideoPlayerBinding`).
- `MediaTypeDetector` chooses HLS vs DASH vs Progressive using extension
  AND optional MIME hint — fixes the prior black-screen bug for query-only
  manifest URLs.
- `MediaDownloadManager` wraps Media3 `DownloadManager` for offline downloads
  (cache lives at `<cacheDir>/media-downloads`).

### Local LLM (Req 5)
- `LlamaCppBridge` (singleton) wraps llama.cpp / kotlinllamacpp JNI. Loads
  `libllama.so` (and optionally `libkotlinllamacpp.so`) lazily and degrades
  to passthrough when the natives or the GGUF model are missing.
- Stages `assets/models/dolphin-3.0-llama3.1-8b.Q4_K_M.gguf` into the app's
  files dir on first init (mmap loader needs a real path).
- `NLPQueryEngine`:
  - `rewriteQuery(q)` — LLM-driven query rewriting; falls back to the input.
  - `startRefinementLoop(liked, intervalMs, onNewQuery)` — continuous
    background refinement seeded from liked items.
  - `analyzeTokens(blob)` — pulls JWTs out of free-form text and decodes them.
- `TokenAnalyzer` decodes JWTs (header/payload/exp/scope) and scores reuse
  potential.

### Build (Req 6)
- `app/build.gradle.kts` adds: media3-database, media3-datasource,
  lifecycle-viewmodel-savedstate, lifecycle-viewmodel-compose,
  compose foundation, jsoup 1.17.2.
- Removed `EngineModule` — every engine class already uses
  `@Inject constructor`, the explicit `@Provides` were duplicate bindings
  and one of them (`provideCloudflareBypassEngine()`) called a no-arg
  constructor that doesn't exist.

### Misc fixes
- `ui/MainActivity.kt` → `ui/activity/MainActivity.kt`.
- `SearchScreen.kt` package corrected to `ui.screens`.
- AndroidManifest xmlns typo (`apk/res/xml/android` → `apk/res/android`).
- Merged duplicate `AggregatorDao` interfaces into `Daos.kt`.

## Where the GGUF lives

Real weights aren't bundled. Drop the file at:
`app/src/main/assets/models/dolphin-3.0-llama3.1-8b.Q4_K_M.gguf`

If the native libs / model are absent the app still runs — `LlamaCppBridge.isAvailable()`
returns false and `NLPQueryEngine` uses simple keyword-frequency fallbacks.
