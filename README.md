# 🏷️ PriceHunt Android App

A native Android app to compare prices across 10+ Indian e-commerce and quick-commerce platforms.

## Features

### 🚀 Real-time Price Comparison
- Search across 10 platforms simultaneously
- Streaming results as each platform responds
- Visual status indicators for each platform

### 📱 Platforms Supported

**Quick Commerce (10-30 min delivery):**
- Zepto
- Blinkit
- Swiggy Instamart
- Flipkart Minutes
- JioMart Quick
- BigBasket

**E-Commerce (1-4 days delivery):**
- Amazon
- Flipkart
- JioMart
- Amazon Fresh

### ⚡ Smart Caching
- Intelligent caching with per-platform TTL
- Quick commerce: 5 minute cache
- E-commerce: 15 minute cache
- Stale-while-revalidate strategy
- Visual cache indicators

### 🎨 Modern UI
- Material 3 Design
- Dark theme optimized
- Smooth animations
- Real-time status updates

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      PRESENTATION LAYER                          │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐        │
│  │  HomeScreen  │   │  Components  │   │   Theme      │        │
│  │  (Compose)   │   │  (Cards,etc) │   │   Colors     │        │
│  └──────┬───────┘   └──────────────┘   └──────────────┘        │
│         │                                                        │
│  ┌──────▼───────┐                                               │
│  │ HomeViewModel│ ← Hilt Injection                              │
│  └──────┬───────┘                                               │
└─────────│───────────────────────────────────────────────────────┘
          │
┌─────────▼───────────────────────────────────────────────────────┐
│                       DATA LAYER                                 │
├─────────────────────────────────────────────────────────────────┤
│  ┌───────────────────┐                                          │
│  │ ProductRepository │ ─── Coordinates scrapers + cache         │
│  └─────────┬─────────┘                                          │
│            │                                                     │
│  ┌─────────▼─────────┐    ┌─────────────────┐                   │
│  │   CacheManager    │────│   Room DB       │                   │
│  │   (TTL logic)     │    │   (CacheDao)    │                   │
│  └───────────────────┘    └─────────────────┘                   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                      SCRAPERS                             │   │
│  ├──────────────────────────────────────────────────────────┤   │
│  │  Amazon  │ Flipkart │ Zepto │ Blinkit │ BigBasket │ ...  │   │
│  │  (HTTP + Jsoup parsing)                                   │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Tech Stack

- **UI**: Jetpack Compose + Material 3
- **DI**: Hilt
- **Network**: OkHttp
- **HTML Parsing**: Jsoup
- **JSON**: Gson
- **Database**: Room
- **Image Loading**: Coil
- **Async**: Kotlin Coroutines + Flow

## Project Structure

```
app/src/main/java/com/pricehunt/
├── PriceHuntApp.kt              # Application class
├── data/
│   ├── model/
│   │   ├── Product.kt           # Data models
│   │   └── Platform.kt          # Platform constants
│   ├── scrapers/
│   │   ├── BaseScraper.kt       # Base scraper class
│   │   └── http/
│   │       ├── AmazonScraper.kt
│   │       ├── FlipkartScraper.kt
│   │       ├── ZeptoScraper.kt
│   │       ├── BlinkitScraper.kt
│   │       ├── InstamartScraper.kt
│   │       ├── BigBasketScraper.kt
│   │       ├── JioMartScraper.kt
│   │       ├── JioMartQuickScraper.kt
│   │       ├── AmazonFreshScraper.kt
│   │       └── FlipkartMinutesScraper.kt
│   ├── repository/
│   │   ├── ProductRepository.kt # Main repository
│   │   └── CacheManager.kt      # Cache management
│   └── local/
│       ├── AppDatabase.kt       # Room database
│       ├── dao/CacheDao.kt      # Cache DAO
│       └── entity/CacheEntity.kt
├── di/
│   └── AppModule.kt             # Hilt module
└── presentation/
    ├── MainActivity.kt
    ├── theme/
    │   ├── Theme.kt
    │   └── Type.kt
    ├── components/
    │   ├── SearchBar.kt
    │   ├── ProductCard.kt
    │   ├── BestDealCard.kt
    │   └── PlatformStatusBar.kt
    └── screens/
        └── home/
            ├── HomeScreen.kt
            └── HomeViewModel.kt
```

## Building

### Requirements
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 34

### Steps

1. Clone the repository:
```bash
git clone <repo-url>
cd price-comparator-android-app
```

2. Open in Android Studio

3. Sync Gradle files

4. Run on device/emulator

## How It Works

1. **User enters search query** → HomeViewModel receives input
2. **Search triggered** → ProductRepository starts parallel scraping
3. **Each scraper**:
   - Checks cache first (returns immediately if fresh)
   - Fetches HTML from platform
   - Parses products using Jsoup
   - Returns results
4. **Results stream** via Kotlin Flow to UI
5. **UI updates** in real-time as each platform responds
6. **Best deal** calculated and highlighted

## Cache Strategy

```
┌─────────────────────────────────────────────────────────────┐
│ Query: "milk" + Platform: "Zepto" + Pincode: "560001"      │
│                          ↓                                  │
│ Cache Key: "milk_Zepto_560001"                             │
│                          ↓                                  │
│ ┌─────────────────────────────────────────────────────┐    │
│ │ Age < TTL (5min)?        → Return cached (fresh)    │    │
│ │ Age < TTL + 2min?        → Return cached (stale)    │    │
│ │                            + refresh in background   │    │
│ │ Age > TTL + 2min?        → Delete, fetch fresh      │    │
│ └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## License

MIT License - Feel free to use and modify!

