package nl.lrvdlinden.bingr

/**
 * Build 175 (versie 1.0.7): downloaden overal vanuit Meer op de detailpagina (telefoon en tablet), ook bij series.
 * Pure logica zonder Android, met unittests (tools/test/Build175LogicTest.kt). Dezelfde regels als Apple
 * (`Shared/Player/Build175.swift`). De webspeler heeft geen downloads.
 */
object Build175Logic {

    // MARK: Stand van een download (los van Downloads, zodat de logica testbaar is)

    enum class State { NONE, QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED }

    /** Downloads.status (queued, downloading, paused, done, failed) of null → State */
    fun state(status: String?): State = when (status) {
        null -> State.NONE
        "queued" -> State.QUEUED
        "downloading" -> State.DOWNLOADING
        "paused" -> State.PAUSED
        "done" -> State.COMPLETED
        else -> State.FAILED
    }

    /** Al binnen of onderweg: niet nog eens toevoegen. Mislukt of gepauzeerd wel (dan hervat hij). */
    fun isPresent(s: State): Boolean = s == State.COMPLETED || s == State.QUEUED || s == State.DOWNLOADING

    // MARK: Film

    enum class MovieMenu {
        /** Uitgegrijsd met de reden ("Deze stream kan niet gedownload worden") */
        UNAVAILABLE,
        DOWNLOAD,
        /** Bezig: pauzeren */
        ACTIVE,
        /** Gepauzeerd of mislukt: hervatten */
        RESUME,
        /** "Gedownload" met een vinkje, en Verwijderen */
        DOWNLOADED,
    }

    /** Een bestaande download gaat voor: die blijft zichtbaar (en te verwijderen), ook als de stream nu niet te downloaden is. */
    fun movieMenu(canDownload: Boolean, state: State): MovieMenu = when (state) {
        State.COMPLETED -> MovieMenu.DOWNLOADED
        State.QUEUED, State.DOWNLOADING -> MovieMenu.ACTIVE
        State.PAUSED, State.FAILED -> MovieMenu.RESUME
        State.NONE -> if (canDownload) MovieMenu.DOWNLOAD else MovieMenu.UNAVAILABLE
    }

    // MARK: Serie

    enum class Scope {
        /** "Dit seizoen downloaden": het seizoen dat op de pagina gekozen is */
        SEASON,
        /** "Volgende 3 afleveringen": vanaf de aflevering die je als volgende kijkt */
        NEXT_THREE,
        /** "Hele serie" (met een bevestiging met het aantal afleveringen) */
        ALL,
    }

    data class Episode(
        val id: String, val season: Int, val number: Int,
        /** Heeft een downloadbare stream (Downloads.canDownload) */
        val downloadable: Boolean = true,
        val state: State = State.NONE,
    )

    const val NEXT_COUNT = 3

    /** Op volgorde (seizoen, aflevering); bij gelijke nummers blijft de volgorde van de lijst (stabiele sortering) */
    fun ordered(eps: List<Episode>): List<Episode> = eps.sortedWith(compareBy<Episode>({ it.season }, { it.number }))

    /**
     * De afleveringen die een keuze toevoegt (id's, op volgorde): alleen downloadbare die nog niet binnen of onderweg zijn.
     * [nextUp] = de aflevering die je als volgende kijkt (null: vanaf het begin). Volgende 3: de eerste drie vanaf
     * [nextUp] die nog niet binnen zijn, zodat nog eens kiezen de drie daarna pakt.
     */
    fun episodes(scope: Scope, eps: List<Episode>, season: Int, nextUp: String?): List<String> {
        val list = ordered(eps)
        val wanted = { e: Episode -> e.downloadable && !isPresent(e.state) }
        return when (scope) {
            Scope.SEASON -> list.filter { it.season == season && wanted(it) }.map { it.id }
            Scope.ALL -> list.filter(wanted).map { it.id }
            Scope.NEXT_THREE -> {
                val start = nextUp?.let { id -> list.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
                list.drop(start).filter(wanted).take(NEXT_COUNT).map { it.id }
            }
        }
    }

    /** Alleen bij Hele serie eerst vragen (met het aantal); seizoen en volgende 3 starten meteen. */
    fun needsConfirmation(scope: Scope, count: Int): Boolean = scope == Scope.ALL && count > 0

    data class SeriesMenu(
        /** Er is minstens één aflevering met een downloadbare stream of een download (anders: uitgegrijsd met de reden) */
        val available: Boolean,
        val season: Int,
        val nextThree: Int,
        val all: Int,
        /** Afleveringen met een download (elke stand), voor Verwijderen */
        val downloaded: Int,
        /** Alle downloadbare afleveringen staan op het toestel: "Gedownload" met een vinkje */
        val complete: Boolean,
    )

    fun seriesMenu(eps: List<Episode>, season: Int, nextUp: String?): SeriesMenu {
        val downloadable = eps.filter { it.downloadable }
        val withDownload = eps.count { it.state != State.NONE }
        return SeriesMenu(
            available = downloadable.isNotEmpty() || withDownload > 0,
            season = episodes(Scope.SEASON, eps, season, nextUp).size,
            nextThree = episodes(Scope.NEXT_THREE, eps, season, nextUp).size,
            all = episodes(Scope.ALL, eps, season, nextUp).size,
            downloaded = withDownload,
            complete = downloadable.isNotEmpty() && downloadable.all { it.state == State.COMPLETED },
        )
    }
}
