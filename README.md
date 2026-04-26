# AggregatorX - Advanced Multi-Provider Search Aggregator

[![Android Build](https://github.com/zimbiss/aggrevation-x/actions/workflows/android.yml/badge.svg)](https://github.com/zimbiss/aggrevation-x/actions/workflows/android.yml)

## 📥 Download

**Get the latest APK from [GitHub Actions](https://github.com/zimbiss/aggrevation-x/actions) → Select latest successful build → Download `AggregatorX-release` artifact**

Or download from the [Releases](https://github.com/zimbiss/aggrevation-x/releases) page when available.

## 🚀 Featuresl

### Multi-Provider Search
- **Concurrent searching** across all configured providers
- **Results grouped by provider** for easy browsing
- **Smart ranking system** that scores and ranks results by relevance
- **Fallback mechanisms** - if one provider fails, others continue
- **Real-time result streaming** - see results as they come in

### 🖱️ Auto-Click Ad Bypass
- **Automatic popup/ad dismissal** - Clicks close buttons automatically
- **Shadow DOM support** - Detects and clicks buttons in shadow DOM
- **Cookie consent.  .handling** - Auto-accepts cookie banners
- **Video player ad skip** - Bypasses video player ads
- **Multi-pass removal** - Multiple attempts to ensure all overlays removed

### Advanced Site Analyzer
- **Security Analysis**: SSL/TLS, CSP, HSTS, X-Frame-Options, Cookie flags
- **DOM Structure Analysis**: Element count, depth, forms, links, scripts
- **Pattern Detection**: Search forms, result lists, pagination, video players
- **Media Detection**: Video players (JWPlayer, VideoJS, HTML5), streaming protocols (HLS, DASH)
- **API Detection**: REST, GraphQL endpoints, JSON feeds
- **Performance Metrics**: Load time, resource count, page size

### 🌙 Pastel Dark Theme (Easy on Eyes)
- **Soft pastel color palette** - Muted cyan, blue, lavender accents
- **Deep restful backgrounds** - Dark charcoal and slate colors
- **Comfortable contrast** - Text designed for long reading sessions
- **Smooth animations** throughout the app
- **Glow effects** - Subtle gradient glows on buttons and inputs
- **Material3 design** - Modern Jetpack Compose UI

### Beautiful Modern UI
- **Easy scrolling** between providers and results
- **Provider cards** with toggle enable/disable
- **Score badges** showing relevance scores
- **Quality indicators** for media content (4K, 1080p, etc.)

### Provider Management
- **Enable/disable providers** with toggle switches
- **Re-analyze individual providers** to update configurations
- **Delete providers** you no longer need
- **Category detection** (Streaming, Torrent, News, Media, API-based)
- **Health tracking** showing success rates and response times

### Settings
- **Add custom URLs** - enter any website to analyze and add
- **Refresh All button** - re-analyze all providers at once
- **Full analysis reports** showing all detected patterns
- **About section** with app information

## 📱 Screenshots

The app features three main tabs:
1. **Search** - Enter search queries and see aggregated results
2. **Providers** - Manage your configured content providers
3. **Settings** - Add new providers and configure the app

## 🛠️ Technical Architecture

### Scraping Engine
- Concurrent provider searching with rate limiting
- Multiple fallback strategies when scraping fails
- User agent rotation
- Retry logic with exponential backoff
- Pattern-based content extraction

### Ranking Engine
- TF-IDF inspired text relevance scoring
- Provider reliability weighting
- Content freshness scoring
- User engagement signals (seeders, views, ratings)
- Quality indicators (4K, 1080p, etc.)

### Site Analyzer
- Deep DOM structure analysis
- Pattern recognition for content structures
- Video player detection (10+ player types)
- Streaming protocol detection (HLS, DASH, RTMP)
- API endpoint discovery

### Database
- Room database for offline persistence
- Stores providers, analyses, and configurations
- Search history tracking
- Efficient query caching

## 🚦 Getting Started

1. **Add a provider**: Go to Settings → Enter a website URL → Click "Analyze & Add Provider"
2. **Enable providers**: Go to Providers tab → Toggle on the providers you want to search
3. **Search**: Go to Search tab → Enter your query → Results appear grouped by provider

## 📦 Dependencies

- **Jetpack Compose** - Modern UI toolkit
- **Hilt** - Dependency injection
- **Room** - Database persistence
- **Jsoup** - HTML parsing and scraping
- **Retrofit/OkHttp** - Network requests
- **Coil** - Image loading
- **Kotlin Coroutines** - Async operations
- **Kotlin Serialization** - JSON handling

## 🔧 Building

```bash
./gradlew assembleDebug
```

## 📄 License

MIT License - See LICENSE file for details

## 🤝 Contributing

Contributions welcome! Please read the contributing guidelines first.
0⁰