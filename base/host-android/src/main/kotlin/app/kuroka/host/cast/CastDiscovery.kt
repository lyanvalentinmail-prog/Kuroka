package app.kuroka.host.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress

/*
 * Finding the Chromecasts of the local network: mDNS (`_googlecast._tcp`) through Android's own
 * NsdManager, which needs no extra permission and no Google Play services. Groups of speakers and
 * Android TVs with a receiver in them are listed too.
 *
 * The structure is Orpheon's (GPL-3.0, © kota-zo, https://github.com/kota-zo/Orpheon,
 * base/app/src/main/java/com/music/spotui/cast/CastDiscovery.kt): before Android 14 one service is
 * resolved at a time, so the others wait in a queue, and a resolve that never answers is given up
 * on and tried once more.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../LICENSE).
 */

/** A Cast device found on the network. [id] is the device's own (its TXT `id`), stable across addresses. */
data class CastDevice(
    val id: String,
    val name: String,
    val model: String,
    val host: InetAddress,
    val port: Int,
    val service: String,
)

object CastDiscovery {
    private const val TYPE = "_googlecast._tcp"
    private const val RESOLVE_MS = 6000L

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    /** The devices found, newest last. Read on the main thread. */
    val devices: StateFlow<List<CastDevice>> get() = _devices

    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var users = 0
    /** Before Android 14 NsdManager resolves one service at a time: the others wait here. */
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    /** Each resolve has a number: the answer of one given up on (or of a stopped search) is ignored. */
    private var generation = 0
    /** Services whose resolve failed once: tried once more, then left. */
    private val retried = HashSet<String>()

    /** Starts looking (each [start] wants one [stop]). Main thread. */
    fun start(context: Context) {
        if (users++ > 0) return
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: run { users = 0; return }
        nsd = manager
        _devices.value = emptyList()
        waiting.clear()
        retried.clear()
        resolving = false
        generation++
        val found = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    if (listener === this) {
                        listener = null
                        users = 0
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    waiting.addLast(info)
                    next()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    val left = _devices.value.filterNot { it.service == info.serviceName }
                    if (left.size != _devices.value.size) _devices.value = left
                }
            }
        }
        listener = found
        runCatching { manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, found) }
            .onFailure { listener = null; users = 0 }
    }

    fun stop() {
        if (users == 0 || --users > 0) return
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        waiting.clear()
        resolving = false
        generation++
        _devices.value = emptyList()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || listener == null) return
        val info = waiting.removeFirstOrNull() ?: return
        val manager = nsd ?: return
        resolving = true
        val gen = generation
        // postDelayed answers whether it was posted, so the timeout has to be a Runnable of its
        // own to be taken back off the queue when the resolve answers first.
        val timeout = Runnable {
            if (gen == generation) {
                resolving = false
                next()
            }
        }
        main.postDelayed(timeout, RESOLVE_MS)
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        main.post {
                            if (gen != generation) return@post
                            main.removeCallbacks(timeout)
                            resolving = false
                            // A device that was busy resolving is worth one more try.
                            if (retried.add(info.serviceName)) waiting.addLast(info)
                            next()
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        main.post {
                            if (gen != generation) return@post
                            main.removeCallbacks(timeout)
                            resolving = false
                            add(info)
                            next()
                        }
                    }
                },
            )
        }.onFailure {
            main.post {
                if (gen != generation) return@post
                main.removeCallbacks(timeout)
                resolving = false
                next()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun add(info: NsdServiceInfo) {
        val host = info.host ?: return
        // An IPv6 link-local address is no good to a Chromecast on the phone's own network.
        if (host !is Inet4Address && host.isLinkLocalAddress) return
        val attributes = runCatching { info.attributes }.getOrDefault(emptyMap())
        fun text(key: String): String = attributes[key]?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() } ?: ""
        val id = text("id").ifBlank { info.serviceName }
        val name = text("fn").ifBlank { info.serviceName }
        val model = text("md")
        val device = CastDevice(id, name, model, host, info.port, info.serviceName)
        val current = _devices.value.toMutableList()
        val at = current.indexOfFirst { it.id == id || it.service == device.service }
        if (at >= 0) current[at] = device else current += device
        _devices.value = current.sortedBy { it.name.lowercase() }
    }
}
