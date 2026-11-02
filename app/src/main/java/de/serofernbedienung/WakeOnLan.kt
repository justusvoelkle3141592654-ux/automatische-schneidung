package de.serofernbedienung

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Wake-on-LAN: sendet ein Magic Packet (6 x 0xFF + 16 x MAC) per UDP-Broadcast an Port 9. */
object WakeOnLan {

    fun parseMac(text: String): ByteArray? {
        val parts = text.trim().split(':', '-')
        if (parts.size != 6) return null
        return try {
            ByteArray(6) { parts[it].toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Muss im Hintergrund-Thread laufen. */
    fun send(context: Context, mac: ByteArray) {
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 1..16) System.arraycopy(mac, 0, packet, i * 6, 6)

        val targets = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        subnetBroadcast(context)?.let { targets += it }

        DatagramSocket().use { s ->
            s.broadcast = true
            for (t in targets) {
                repeat(3) { s.send(DatagramPacket(packet, packet.size, t, 9)) }
            }
        }
    }

    private fun subnetBroadcast(context: Context): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val addr = la.address.address
        val prefix = la.prefixLength
        val ip = ((addr[0].toInt() and 0xFF) shl 24) or ((addr[1].toInt() and 0xFF) shl 16) or
            ((addr[2].toInt() and 0xFF) shl 8) or (addr[3].toInt() and 0xFF)
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val bc = ip or mask.inv()
        return InetAddress.getByAddress(
            byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte())
        )
    }
}
