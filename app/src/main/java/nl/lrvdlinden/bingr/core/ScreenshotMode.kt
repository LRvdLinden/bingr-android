package nl.lrvdlinden.bingr

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

// MARK: - Screenshotmodus (Google Play)
//
// Tegenhanger van Shared/App/ScreenshotMode.swift in iptv-ios-appletv. Alleen te bereiken in een build met
// -PBINGR_SCREENSHOTS=true (BuildConfig.SCREENSHOTS, alleen de workflow playstore-screenshots.yml maakt die) én met de
// intent-extra `bingr.screenshots` = true; `bingr.screen` kiest het scherm: home, detail, live, guide, movies,
// picker, categories, multiview. In gewone builds is BuildConfig.SCREENSHOTS false en doet niets hieronder iets.
//
// In deze modus: een demobibliotheek in het geheugen met echte films met een vrije licentie (Blender open movies, CC BY;
// stille films die in de EU en de VS publiek domein zijn). De afbeeldingen zet de workflow vooraf in de assets van de
// screenshot-build (map screenshot-media, opgehaald van Wikimedia Commons met licentiecontrole; nooit in de repo of in
// gewone builds). Zenders, gids en profielen zijn verzonnen (geen echte zenders of merken). Geen netwerk (Http.client
// weigert alles), geen account, geen sync en niets opgeslagen.

object Screenshots {
    /** Alleen in de screenshot-build én met de intent-extra (zie boven). */
    var isActive: Boolean = false
        private set

    /** Het scherm dat direct open moet (`bingr.screen`). */
    var screen: String = "home"
        private set

    fun init(intent: Intent?) {
        if (!BuildConfig.SCREENSHOTS || intent == null) return
        if (!intent.getBooleanExtra("bingr.screenshots", false)) return
        isActive = true
        screen = (intent.getStringExtra("bingr.screen") ?: "home").lowercase()
    }
}

object ScreenshotDemo {
    private const val SOURCE_ID = "b1a9e5c0-0000-4000-8000-000000000001"
    private const val ALEX = "b1a9e5c0-0000-4000-8000-0000000000a1"
    private const val SAM = "b1a9e5c0-0000-4000-8000-0000000000a2"
    private const val NOOR = "b1a9e5c0-0000-4000-8000-0000000000a3"
    private const val GUEST = "b1a9e5c0-0000-4000-8000-0000000000a4"
    private const val HERO_MOVIE = "m01"
    private const val DETAIL_SERIES = "s01"
    private const val ASSET_DIR = "screenshot-media"

    var library = Library()
        private set

    private class DemoTitle(
        val id: String, val title: String, val year: Int, val genres: List<String>, val minutes: Int,
        val director: String, val plot: String, val group: String, val episodes: List<String> = emptyList(),
    )

    // Blender open movies (CC BY, © Blender Foundation | blender.org) en films die in de EU én de VS publiek domein zijn
    // (vóór 1931, alle makers meer dan 70 jaar overleden). Geen scores of leeftijden (die zouden verzonnen zijn).
    private val movies = listOf(
        DemoTitle("m01", "Sintel", 2010, listOf("Fantasy", "Animation"), 15, "Colin Levy",
            "A lonely young woman befriends a baby dragon she calls Scales. When he is taken from her, she sets out on a long and dangerous quest to find him again.",
            "Animation"),
        DemoTitle("m02", "Tears of Steel", 2012, listOf("Sci-Fi", "Action"), 12, "Ian Hubert",
            "In a future Amsterdam, a group of warriors and scientists gathers at the Oude Kerk for a desperate attempt to save the world from destructive robots.",
            "Sci-Fi"),
        DemoTitle("m03", "Big Buck Bunny", 2008, listOf("Comedy", "Animation"), 10, "Sacha Goedegebure",
            "A big, gentle rabbit enjoys a sunny day in the forest, until three bullying rodents push him too far and he plans a comic revenge.",
            "Family"),
        DemoTitle("m04", "Cosmos Laundromat", 2015, listOf("Sci-Fi", "Animation"), 12, "Mathieu Auvray",
            "On a desolate island, a suicidal sheep named Franck meets a quirky salesman who offers him the gift of a lifetime: many lives.",
            "Sci-Fi"),
        DemoTitle("m05", "Spring", 2019, listOf("Fantasy", "Animation"), 8, "Andy Goralczyk",
            "A shepherd girl and her dog face ancient spirits in order to continue the cycle of life.", "Animation"),
        DemoTitle("m06", "Sprite Fright", 2021, listOf("Horror", "Comedy"), 10, "Matthew Luhn",
            "When a group of rowdy teenagers trek into an isolated forest, they discover peaceful mushroom creatures that turn out to be an unexpected force of nature.",
            "Family"),
        DemoTitle("m07", "Coffee Run", 2020, listOf("Drama", "Animation"), 3, "Hjalti Hjálmarsson",
            "Fuelled by caffeine, a young woman runs through the bittersweet memories of her past relationship.", "Animation"),
        DemoTitle("m08", "Charge", 2022, listOf("Action", "Animation"), 4, "Hjalti Hjálmarsson",
            "In an energy-starved future, an old man breaks into a heavily guarded facility to steal fuel, and finds out he is not alone.",
            "Action"),
        DemoTitle("m09", "Elephants Dream", 2006, listOf("Sci-Fi", "Animation"), 11, "Bassam Kurdali",
            "Old Proog guides young Emo through the endless rooms and machines of a strange world that only one of them seems to understand.",
            "Sci-Fi"),
        DemoTitle("m11", "Nosferatu", 1922, listOf("Horror", "Silent"), 94, "F. W. Murnau",
            "Estate agent Thomas Hutter travels to the Carpathian mountains to sell a house to the mysterious Count Orlok, who brings death and plague to the town of Wisborg.",
            "Silent Classics"),
        DemoTitle("m12", "A Trip to the Moon", 1902, listOf("Sci-Fi", "Silent"), 13, "Georges Méliès",
            "A group of astronomers is fired from a giant cannon to the Moon, where they meet the strange Selenites. Georges Méliès' famous fantasy.",
            "Silent Classics"),
        DemoTitle("m13", "The Great Train Robbery", 1903, listOf("Western", "Silent"), 12, "Edwin S. Porter",
            "A gang of outlaws holds up a steam train and robs its passengers, with a posse in hot pursuit.", "Silent Classics"),
    )

    private val series = listOf(
        DemoTitle("s01", "Les Vampires", 1915, listOf("Crime", "Mystery", "Silent"), 45, "Louis Feuillade",
            "Journalist Philippe Guérande takes on the Vampires, a secret gang of criminals led by the Grand Vampire and the mysterious Irma Vep, who hold Paris in their grip. Louis Feuillade's serial in ten episodes.",
            "Silent Serials",
            listOf("The Severed Head", "The Ring That Kills", "The Red Codebook", "The Spectre", "Dead Man's Escape",
                "Hypnotic Eyes", "Satanas", "The Thunder Master", "The Poisoner", "The Terrible Wedding")),
        DemoTitle("s02", "Judex", 1916, listOf("Crime", "Adventure", "Silent"), 45, "Louis Feuillade",
            "The masked avenger Judex sets out to punish the ruthless banker Favraux, and falls in love with his daughter Jacqueline. Louis Feuillade's serial in twelve parts.",
            "Silent Serials",
            listOf("The Mysterious Shadow", "The Atonement", "The Fantastic Hounds", "The Secret of the Tomb",
                "The Tragic Mill", "The Licorice Kid", "The Woman in Black", "The Underground Passages of Château-Rouge",
                "When the Child Appeared", "Jacqueline's Heart", "The Water Sprite", "Love's Forgiveness")),
    )

    private val episodePlots = mapOf(
        "s01" to listOf(
            "Philippe Guérande, a reporter at a Paris newspaper, starts to investigate the gang that calls itself the Vampires.",
            "The Vampires strike again, and Guérande and his friend Mazamette follow a trail across Paris.",
            "A coded notebook falls into the wrong hands, and the gang will do anything to get it back.",
            "Irma Vep and the Vampires plan their boldest move yet.",
            "Guérande sets a trap, but the Vampires are always one step ahead.",
            "A new rival enters the game and the gang is forced to change its plans."),
        "s02" to listOf(
            "A mysterious figure in a black cloak watches the banker Favraux.",
            "Favraux receives a warning: he has until midnight to make amends.",
            "Jacqueline learns the truth about her father's past.",
            "Judex and his brother follow the kidnappers through the countryside.",
            "The adventuress Diana Monti spins a web of her own.",
            "A young street urchin turns out to be an unexpected ally."),
    )

    /** Verzonnen zenders (id, naam, groep, terugkijkdagen); logo's maakt de workflow (logo-<id>.png). */
    private class DemoChannel(val id: String, val name: String, val group: String, val catchup: Int)

    private val channels = listOf(
        DemoChannel("c01", "News 24", "EN | News", 7),
        DemoChannel("c02", "Actua", "EN | News", 7),
        DemoChannel("c03", "Prime One", "EN | Entertainment", 7),
        DemoChannel("c04", "Family+", "EN | Entertainment", 3),
        DemoChannel("c05", "Comedy Lane", "EN | Entertainment", 0),
        DemoChannel("c06", "Cinema Nights", "EN | Movies", 0),
        DemoChannel("c07", "Stadium 1", "EN | Sports", 3),
        DemoChannel("c08", "Matchday", "EN | Sports", 0),
        DemoChannel("c09", "Motor Max", "EN | Sports", 0),
        DemoChannel("c10", "Explore", "EN | Documentary", 7),
        DemoChannel("c11", "Planet Wild", "EN | Documentary", 0),
        DemoChannel("c12", "Time Travel", "EN | Documentary", 0),
        DemoChannel("c13", "Kids Club", "EN | Kids", 0),
        DemoChannel("c14", "Little Stars", "EN | Kids", 0),
        DemoChannel("c15", "Tune Up", "EN | Music", 0),
        DemoChannel("c16", "Weather Now", "EN | News", 0),
        // categorieën die in de demo uit staan (scherm Categorieën)
        DemoChannel("c17", "Info Sept", "FR | Info", 0),
        DemoChannel("c18", "Sportwelt", "DE | Sport", 0),
        DemoChannel("c19", "Deportes Uno", "ES | Deportes", 0),
        DemoChannel("c20", "Cine Classique", "FR | Cinéma", 0),
    )
    private val hiddenGroups = listOf("FR | Info", "DE | Sport", "ES | Deportes", "FR | Cinéma")

    private val programTitles = mapOf(
        "EN | News" to listOf("Evening Journal", "World Tonight", "Behind the Headlines", "Morning Brief", "Market Watch", "The Daily Wrap", "Newsroom Live"),
        "EN | Entertainment" to listOf("Showbiz Today", "Our Village", "Late Night Talk", "Kitchen Heroes", "The Big Quiz", "Home Makeover", "Stand-Up Night"),
        "EN | Movies" to listOf("Friday Feature", "Classic Cinema", "Midnight Movie", "Director's Cut", "Double Bill"),
        "EN | Sports" to listOf("Match Day Live", "Speed Week", "Goal Rush", "Qualifying Live", "Grand Final", "Sports Desk", "Extra Time"),
        "EN | Documentary" to listOf("Gold Hunters", "Wild Planet", "Ocean Giants", "Lost Cities", "Engineering Marvels", "Life in the Ice"),
        "EN | Kids" to listOf("Tiny Explorers", "Robo Pals", "Story Time", "Bubble Bay", "Doodle Club", "Space Pups"),
        "EN | Music" to listOf("Top Tracks", "Live Sessions", "Throwback Hits", "Acoustic Hour", "Chart Show"),
    )

    // MARK: afbeeldingen (assets/screenshot-media, alleen in de screenshot-build)

    private var assets: Set<String> = emptySet()

    private fun media(name: String): String? =
        if (name in assets) "file:///android_asset/$ASSET_DIR/$name" else null

    private fun poster(id: String) = media("$id-poster.jpg")
    private fun keyArt(id: String) = media("$id-keyart.jpg") ?: poster(id)
    private fun backdrop(id: String) = media("$id-backdrop.jpg") ?: keyArt(id)
    fun liveStill(channelID: String): String? = media("live-$channelID.jpg")

    // MARK: starten

    /** Vanuit MainActivity.onCreate (vóór setContent): demo opbouwen en het gevraagde scherm openen. */
    fun start() {
        if (!Screenshots.isActive) return
        assets = runCatching { AppState.appContext.assets.list(ASSET_DIR)?.toSet() }.getOrNull() ?: emptySet()
        buildLibrary()
        Prefs.put(HeroTrailer.PREF, false)
        val screen = Screenshots.screen
        Prefs.put("ui.startTab", when (screen) { "live", "guide" -> 1; "movies", "top10" -> 2; "series" -> 3; else -> 0 })

        val source = PlaylistSource(id = SOURCE_ID, name = "Alex", kind = PlaylistKind.M3U,
            avatar = PlaylistAvatar("📺", 0), m3uURL = "http://demo.invalid/playlist.m3u")
        val profiles = listOf(
            ViewerProfile(id = ALEX, name = "Alex", avatar = PlaylistAvatar("📺", 0)),
            ViewerProfile(id = SAM, name = "Sam", avatar = PlaylistAvatar("🌐", 1)),
            ViewerProfile(id = NOOR, name = "Noor", avatar = PlaylistAvatar("kid03", 2), isKids = true),
            ViewerProfile(id = GUEST, name = "Guest", avatar = PlaylistAvatar("👑", 4)),
        )
        val now = System.currentTimeMillis()
        fun key(item: String) = "$SOURCE_ID|$item"
        fun entry(title: String, art: String?, fraction: Double, minutes: Int, agoSeconds: Long) = ProgressEntry(
            fraction = fraction, position = minutes * 60.0 * fraction, updated = now - agoSeconds * 1000,
            title = title, artwork = art, playlistID = SOURCE_ID, profileID = ALEX)
        val progress = mapOf(
            "s01-1-4" to entry("Les Vampires", poster("s01"), 0.41, 45, 1800),
            "m02" to entry("Tears of Steel", poster("m02"), 0.62, 12, 3600),
            "m11" to entry("Nosferatu", poster("m11"), 0.35, 94, 7200),
            "m05" to entry("Spring", poster("m05"), 0.7, 8, 86400),
            "s02-1-3" to entry("Judex", poster("s02"), 0.25, 45, 2 * 86400),
            "m03" to entry("Big Buck Bunny", poster("m03"), 0.8, 10, 3 * 86400),
        )
        val watched = HashSet<String>()
        for (e in 1..3) { watched.add("s01-1-$e"); watched.add(Store.episodeKey("s01", 1, e)) }
        val order = if (screen == "movies" || screen == "top10")
            listOf("top10" to true, "mylist" to true, "foryou" to true, "browse" to true, "live" to true, "continue" to false)
        else listOf("continue" to true, "top10" to true, "mylist" to true, "live" to true, "foryou" to true, "browse" to true)
        val rows = order.map { HomeRow(it.first, it.second) }.toMutableList()
        for (k in HomeLayouts.fixedKeys) if (rows.none { it.key == k }) rows.add(HomeRow(k, false))

        Store.applyScreenshotDemo(
            Store.ScreenshotData(
                playlists = listOf(source), profiles = profiles, mainProfile = ALEX, active = ALEX,
                favorites = listOf("c01", "c03", "c07", "c10", "c13", "c06", "c02").map(::key).toSet(),
                myList = listOf("m04", "m06", "s02", "m12", "m09", "m13", "m08").map(::key).toSet(),
                progress = progress, watched = watched,
                recent = listOf("c03", "c07", "c01").map(::key),
                hiddenCategories = hiddenGroups.map { "$SOURCE_ID|$it" }.toSet(),
                homeLayout = HomeLayout(rows, now),
            )
        )

        AppState.pendingLastChannel = false
        if (screen == "picker") { AppState.stage = AppState.Stage.PICKER; return }
        AppState.lastPlaylistID = SOURCE_ID
        AppState.stage = AppState.Stage.MAIN
        AppScope.launch {
            Playlists.select(source)
            delay(1_500)
            val lib = Playlists.library
            when (screen) {
                "detail" -> lib.series.firstOrNull { it.id == DETAIL_SERIES }?.let { ScreensNav.detail(DetailTarget.SeriesT(it)) }
                "categories" -> ScreensNav.push(ScreenEntry.Categories(Filter.ALL))
                "multiview" -> {
                    val start = listOf("c03", "c10", "c06", "c13").mapNotNull { id -> lib.channels.firstOrNull { it.id == id } }
                    AppState.openMultiview(start, lib.channels)
                }
                "live" -> if (!AppState.isTV) lib.channels.firstOrNull { it.id == "c03" }?.let { ChannelPreview.focus(it) }
                else -> {}
            }
        }
    }

    // MARK: bibliotheek

    private fun buildLibrary() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val movieList = movies.mapIndexed { i, t ->
            // ouder dan een maand: geen "Recently added"-labels; geen score (volgorde Top 10 = addedAt)
            Movie(id = t.id, title = t.title, year = t.year, poster = poster(t.id), group = t.group,
                streamURL = "http://demo.invalid/movie/${t.id}.mp4", plot = t.plot, addedAt = now - (60 + i * 3) * day)
        }
        val seriesList = series.mapIndexed { i, t ->
            val stills = assets.filter { it.startsWith("${t.id}-still") }.sorted().map { "file:///android_asset/$ASSET_DIR/$it" }
            val plots = episodePlots[t.id].orEmpty()
            val eps = t.episodes.mapIndexed { n, title ->
                Episode(id = "${t.id}-1-${n + 1}", seriesID = t.id, season = 1, number = n + 1, title = title,
                    streamURL = "http://demo.invalid/series/${t.id}/1/${n + 1}.mp4",
                    still = if (stills.isEmpty()) null else stills[n % stills.size],
                    plot = if (plots.isEmpty()) null else plots[n % plots.size],
                    durationSeconds = t.minutes * 60)
            }
            Series(id = t.id, title = t.title, year = t.year, poster = poster(t.id), group = t.group, plot = t.plot,
                episodes = eps, addedAt = now - (60 + i * 2) * day)
        }
        // gids rond "nu": per zender programma's vanaf 5 uur geleden tot 14 uur vooruit
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val start0 = cal.timeInMillis - 5 * 3_600_000L
        val durations = listOf(30, 60, 45, 90, 60, 30, 120, 60)
        val epg = HashMap<String, List<EPGProgram>>()
        val channelList = channels.mapIndexed { i, c ->
            val titles = programTitles[c.group] ?: programTitles.getValue("EN | Entertainment")
            var t = start0 + ((i * 15) % 60) * 60_000L - 1_800_000L
            var k = i
            val list = ArrayList<EPGProgram>()
            while (t < now + 14 * 3_600_000L) {
                val dur = durations[k % durations.size]
                val title = titles[k % titles.size]
                list.add(EPGProgram(channelID = c.id, title = title,
                    description = "$title: fresh episode with highlights, guests and the stories everyone will be talking about tomorrow.",
                    start = t, end = t + dur * 60_000L))
                t += dur * 60_000L
                k++
            }
            epg[c.id] = list
            Channel(id = c.id, name = c.name, logo = media("logo-${c.id}.png"), group = c.group,
                streamURL = "http://demo.invalid/live/${c.id}.ts", epgID = c.id, number = i + 1, catchupDays = c.catchup)
        }
        library = Library(channels = channelList, movies = movieList, series = seriesList, epg = epg)
    }

    // MARK: voor de app

    /** Billboard op Home: altijd dezelfde titel. */
    fun hero(filter: Filter, lib: Library): DetailTarget? = when (filter) {
        Filter.SERIES -> lib.series.firstOrNull { it.id == DETAIL_SERIES }?.let { DetailTarget.SeriesT(it) }
        else -> lib.movies.firstOrNull { it.id == HERO_MOVIE }?.let { DetailTarget.MovieT(it) }
    }

    private fun find(title: String, isShow: Boolean): DemoTitle? =
        (if (isShow) series else movies).firstOrNull { it.title.equals(title, ignoreCase = true) }

    private fun runtime(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    /** Gegevens zoals van IMDb, maar zonder score en leeftijd: jaar, genres, duur, regie en beschrijving. */
    fun metadata(title: String, isShow: Boolean): TitleMetadata? {
        val t = find(title, isShow) ?: return null
        return TitleMetadata(imdbID = "", title = t.title, year = t.year.toString(),
            runtime = if (isShow) null else runtime(t.minutes), genre = t.genres.joinToString(", "),
            director = t.director, plot = t.plot, poster = keyArt(t.id), type = if (isShow) "series" else "movie",
            totalSeasons = if (isShow) 1 else null)
    }

    /** Detailpagina zonder netwerk: achtergrond, genres, duur, regie en afleveringsinfo. */
    fun details(target: DetailTarget): TitleDetails? {
        val t = find(target.title, target.isSeries) ?: return null
        val d = TitleDetails(title = t.title, year = t.year.toString(), overview = t.plot, genres = t.genres,
            poster = keyArt(t.id), backdrop = backdrop(t.id), director = t.director,
            runtime = if (target.isSeries) null else runtime(t.minutes), seasonCount = if (target.isSeries) 1 else null)
        if (target is DetailTarget.SeriesT) {
            d.episodeInfo = target.series.episodes.associate { e ->
                "${e.season}-${e.number}" to TitleDetails.EpisodeInfo(e.title, e.plot, e.still, null, t.minutes)
            }
        }
        return d
    }
}

/** Beeld van een zender in de screenshotmodus (voorbeeld in Live TV en Multiview), in plaats van een stream. */
@Composable
fun ScreenshotStill(channel: Channel, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black)) {
        RemoteImage(ScreenshotDemo.liveStill(channel.id), Modifier.matchParentSize())
        coil.compose.AsyncImage(
            model = channel.logo, contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).fillMaxWidth(0.18f).aspectRatio(16f / 9f),
            contentScale = ContentScale.Fit, alpha = 0.9f,
        )
    }
}
