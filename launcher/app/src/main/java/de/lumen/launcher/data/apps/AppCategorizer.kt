package de.lumen.launcher.data.apps

/**
 * Assigns apps to App Library categories.
 *
 * 1. The category declared by the app itself (ApplicationInfo.category, Android 8+) wins when present.
 * 2. Otherwise a keyword table over package name and label is used.
 * 3. Everything else ends up in [AppCategory.OTHER].
 *
 * Pure Kotlin so that it can be unit tested on the JVM.
 */
object AppCategorizer {

    // Values of android.content.pm.ApplicationInfo.CATEGORY_* (stable public API constants).
    const val CATEGORY_UNDEFINED = -1
    const val CATEGORY_GAME = 0
    const val CATEGORY_AUDIO = 1
    const val CATEGORY_VIDEO = 2
    const val CATEGORY_IMAGE = 3
    const val CATEGORY_SOCIAL = 4
    const val CATEGORY_NEWS = 5
    const val CATEGORY_MAPS = 6
    const val CATEGORY_PRODUCTIVITY = 7
    const val CATEGORY_ACCESSIBILITY = 8

    private val keywordTable: List<Pair<AppCategory, List<String>>> = listOf(
        AppCategory.COMMUNICATION to listOf(
            "whatsapp", "telegram", "signal", "messenger", "messaging", "message", "sms", "mms",
            "dialer", "phone", "contacts", "kontakte", "telefon", "nachrichten", "threema",
            "viber", "skype", "teams", "zoom", "discord", "slack", "mail", "gmail", "outlook",
            "email", "e-mail", "duo", "meet", "wire", "element",
        ),
        AppCategory.SOCIAL to listOf(
            "instagram", "facebook", "twitter", "x.android", "tiktok", "musically", "snapchat",
            "reddit", "linkedin", "pinterest", "mastodon", "bluesky", "threads", "tumblr",
            "bereal", "xing", "social",
        ),
        AppCategory.GAMES to listOf("game", "games", "spiel", "puzzle", "chess", "schach", "supercell", "king.", "rovio"),
        AppCategory.ENTERTAINMENT to listOf(
            "netflix", "youtube", "spotify", "music", "musik", "video", "player", "prime",
            "disney", "twitch", "podcast", "radio", "tv", "mediathek", "deezer", "soundcloud",
            "audible", "tidal", "joyn", "zdf", "ard", "film", "movie",
        ),
        AppCategory.CREATIVITY to listOf(
            "camera", "kamera", "photo", "foto", "gallery", "galerie", "snapseed", "lightroom",
            "editor", "picsart", "canva", "capcut", "draw", "paint", "sketch", "studio", "recorder",
        ),
        AppCategory.PRODUCTIVITY to listOf(
            "docs", "sheets", "slides", "office", "word", "excel", "powerpoint", "notion",
            "evernote", "keep", "notes", "notizen", "todo", "tasks", "calendar", "kalender",
            "drive", "dropbox", "onedrive", "bank", "sparkasse", "paypal", "finance", "finanz",
            "wallet", "pay", "invest", "trade", "broker", "n26", "revolut", "comdirect", "ing.",
            "dkb", "volksbank", "authenticator", "pdf", "scanner",
        ),
        AppCategory.UTILITIES to listOf(
            "settings", "einstellungen", "clock", "uhr", "alarm", "calculator", "rechner",
            "files", "dateien", "filemanager", "documentsui", "weather", "wetter", "flashlight",
            "taschenlampe", "compass", "kompass", "vpn", "launcher", "keyboard", "tastatur",
            "browser", "chrome", "firefox", "brave", "opera", "duckduckgo", "samsung.android.app",
            "security", "cleaner", "backup", "tool", "utility", "store", "vending", "packageinstaller",
            "magnifier", "lupe", "voice", "assistant", "google.android.googlequicksearchbox", "translate",
        ),
        AppCategory.READING to listOf(
            "news", "nachrichten", "zeitung", "spiegel", "tagesschau", "bild", "zeit", "faz",
            "kindle", "books", "bücher", "buecher", "reader", "wikipedia", "rss", "feedly", "medium",
            "magazine", "pocket", "tolino",
        ),
        AppCategory.SHOPPING to listOf(
            "amazon.mshop", "shopping", "shop", "ebay", "zalando", "otto", "aliexpress", "temu",
            "shein", "lieferando", "wolt", "uber.eats", "ubereats", "deliveroo", "food", "rewe",
            "lidl", "aldi", "edeka", "kaufland", "ikea", "kleinanzeigen", "vinted", "etsy", "dm.",
        ),
        AppCategory.TRAVEL to listOf(
            "maps", "karten", "navigation", "waze", "uber", "bolt", "taxi", "db.", "bahn",
            "navigator", "flix", "booking", "airbnb", "trip", "travel", "flight", "lufthansa",
            "ryanair", "eurowings", "hotel", "komoot", "citymapper", "transit", "mvg", "bvg",
        ),
        AppCategory.HEALTH to listOf(
            "health", "gesundheit", "fit", "fitness", "workout", "strava", "runtastic", "adidas.running",
            "sleep", "meditation", "calm", "headspace", "yoga", "step", "samsung.health", "garmin",
            "fitbit", "myfitnesspal", "yazio", "doctor", "arzt", "apotheke", "tk.", "aok",
        ),
        AppCategory.EDUCATION to listOf(
            "duolingo", "babbel", "learn", "lernen", "school", "schule", "edu", "quizlet",
            "anki", "khan", "coursera", "udemy", "dictionary", "wörterbuch", "woerterbuch", "leo.",
            "dict", "photomath", "simpleclub",
        ),
    )

    fun categorize(packageName: String, label: String, systemCategory: Int): AppCategory {
        fromSystemCategory(systemCategory)?.let { return it }
        val haystack = normalizeForSearch("$packageName $label")
        for ((category, keywords) in keywordTable) {
            if (keywords.any { haystack.contains(it) }) return category
        }
        return AppCategory.OTHER
    }

    private fun fromSystemCategory(c: Int): AppCategory? = when (c) {
        CATEGORY_GAME -> AppCategory.GAMES
        CATEGORY_AUDIO, CATEGORY_VIDEO -> AppCategory.ENTERTAINMENT
        CATEGORY_IMAGE -> AppCategory.CREATIVITY
        CATEGORY_SOCIAL -> AppCategory.SOCIAL
        CATEGORY_NEWS -> AppCategory.READING
        CATEGORY_MAPS -> AppCategory.TRAVEL
        CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
        CATEGORY_ACCESSIBILITY -> AppCategory.UTILITIES
        else -> null
    }
}
