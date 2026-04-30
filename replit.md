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

### Token auto-injection (NEW)
- New `auth_tokens` table (DB v3, `MIGRATION_2_3`) backed by `AuthTokenDao`.
- `TokenStore` (singleton): per-host in-memory cache of captured tokens with
  lifecycle states UNTESTED → ACTIVE / FAILED / EXPIRED. Exposes
  `bestTokenForHost(host)` to the OkHttp interceptor.
- `TokenInjectorInterceptor` is wired into the global OkHttp client. It picks
  the best captured token for the request's host, injects it as
  `Authorization: Bearer <value>` (or under whatever header the token was
  captured with), and reports success / failure back so failed tokens get
  evicted automatically.
- `HeadlessBrowserHelper` now feeds every captured tokens map AND the raw
  HTML (for JWT regex sweeps) into `TokenStore` after each navigation.

### Smart test queries (NEW)
- `SmartQueryEngine` synthesizes a search query from (1) the user's liked
  titles, (2) recently cached titles, (3) a built-in seed list — so the
  app can run a meaningful scrape on a cold install.
- `SearchViewModel.runSmartTestSearch()` triggers it. The "Smart test
  search" button on the Search screen fires it.

### Downloads UI (NEW)
- `DownloadsViewModel` exposes `MediaDownloadManager.downloads` plus
  `enqueue / pause / resume / remove` callbacks.
- `DownloadsScreen` renders a live list with progress bars + per-row
  controls. Reachable from the new bottom nav bar in `MainActivity`.
- Each result row in `SearchScreen` now has a Download icon that
  enqueues the row's video URL into the manager.

### Bottom navigation
- `MainActivity` now wraps the `NavHost` in a `Scaffold` with a
  `NavigationBar`. Two destinations: `search` (default) and `downloads`.

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

Real weights aren't bundled. **All you need to do is drop the file at:**

```
app/src/main/assets/models/dolphin-3.0-llama3.1-8b.Q4_K_M.gguf
```

The exact filename is enforced by `LlamaCppBridge.MODEL_ASSET_NAME`. On first
init the file is staged from `assets/` into the app's private `filesDir/` so
llama.cpp's mmap loader can open it by absolute path. No code changes needed.

If the native libs / model are absent the app still runs — `LlamaCppBridge.isAvailable()`
returns false and the AI features fall back automatically:

  - `NLPQueryEngine.rewriteQuery()` returns the original query.
  - `NLPQueryEngine.startRefinementLoop()` falls back to keyword frequency.
  - `SmartQueryEngine` still works — it doesn't depend on the LLM.
  - `TokenStore` still captures, injects, tests and evicts tokens.
