package de.serofernbedienung

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Einfache Suche: prüft im eigenen /24-Netz, bei welchen Adressen Port 8002 offen ist.
 * Ein offener Port beweist nicht, dass es ein Samsung-Fernseher ist – die Treffer sind Kandidaten.
 */
object NetworkScan {

    /** Muss im Hintergrund-Thread laufen. */
    fun scan(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        val own = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }
            ?: return emptyList()
        val b = own.address
        val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        val ownIp = own.hostAddress

        val found = Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(32)
        for (i in 1..254) {
            val ip = prefix + i
            if (ip == ownIp) continue
            pool.execute {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, TvConnection.PORT), 400)
                        found += ip
                    }
                } catch (_: Exception) {
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return found.sortedBy { it.substringAfterLast('.').toInt() }
    }
}
