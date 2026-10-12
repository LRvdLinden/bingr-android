package nl.lrvdlinden.bingr

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tegelijk kijken (build 51, docs/ACCOUNT.md): per apparaat telt één stream. De speler (of multiview) is de
 * eigenaar; [begin] stuurt stream_start, elke 30 s volgt stream_ping (ook bij pauze), [end] stuurt stream_stop.
 * Naar de achtergrond zonder beeld-in-beeld of Chromecast = stream_stop; terug naar de voorgrond = opnieuw starten.
 * Netwerkfouten blokkeren nooit; alleen een 403 stream_limit stopt het afspelen via [Owner.onBlocked].
 * Alle aanvragen gaan op volgorde door één wachtrij, zodat stop en start bij zappen niet door elkaar lopen.
 */
object StreamSession {
    data class Info(val title: String, val kind: String, val contentId: String)

    class Owner(val info: () -> Info, val onBlocked: (Account.StreamList) -> Unit)

    private var owner: Owner? = null
    /** Op de achtergrond gezet: stream_stop is al verstuurd. */
    private var suspended = false
    private var pingJob: Job? = null
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var observing = false

    init {
        AppScope.launch {
            for (op in queue) {
                try { op() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    private val enabled: Boolean get() = Account.isSignedIn && Account.configured

    private fun enqueue(op: suspend () -> Unit) { queue.trySend(op) }

    fun begin(o: Owner) {
        installObserver()
        owner = o
        suspended = false
        if (!enabled) return
        val info = o.info()
        enqueue { startNow(o, info) }
        startPing()
    }

    fun end(o: Owner) {
        if (owner !== o) return
        owner = null
        pingJob?.cancel(); pingJob = null
        if (suspended) { suspended = false; return }
        if (enabled) enqueue { Account.streamStop() }
    }

    private suspend fun startNow(o: Owner, info: Info) {
        if (owner !== o || suspended) return
        val p = Store.activeProfile
        val r = Account.streamStart(p?.id ?: Store.activeProfileID ?: "", p?.name ?: "", info.title, info.kind, info.contentId)
        if (r is Account.StreamStart.Limit && owner === o) {
            owner = null
            pingJob?.cancel(); pingJob = null
            o.onBlocked(r.list)
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = AppScope.launch {
            while (isActive) {
                delay(30_000L)
                val o = owner ?: break
                if (suspended || !enabled) continue
                enqueue { ping(o) }
            }
        }
    }

    private suspend fun ping(o: Owner) {
        if (owner !== o || suspended) return
        val info = o.info()
        // false = de server telt deze stream niet meer (bijv. te lange pauze): opnieuw starten
        if (Account.streamPing(info.title) == false && owner === o) startNow(o, info)
    }

    private fun installObserver() {
        if (observing) return
        observing = true
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, e ->
                    when (e) {
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                            if (owner != null && !suspended && !AppState.inPiP && !Casting.connected) {
                                suspended = true
                                if (enabled) enqueue { Account.streamStop() }
                            }
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_START -> {
                            val o = owner
                            if (o != null && suspended) {
                                suspended = false
                                if (enabled) { val info = o.info(); enqueue { startNow(o, info) } }
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    }
}
