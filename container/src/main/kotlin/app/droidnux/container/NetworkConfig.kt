package app.droidnux.container

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Network settings the guest needs from Android. glibc in the container can't
 * use Android's resolver, so /etc/resolv.conf gets the DNS servers of the
 * active network (local resolvers, VPNs), with public ones as a fallback.
 */
object NetworkConfig {

    private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

    fun dnsServers(context: Context): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val properties = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }
        val servers = properties?.dnsServers.orEmpty()
            // IPv4 first: many networks route IPv6 DNS poorly; glibc tries in order.
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .map(InetAddress::getHostAddress)
            .filterNotNull()
            .map { it.substringBefore('%') } // scoped link-local addresses
        return (servers + FALLBACK_DNS).distinct().take(MAX_NAMESERVERS)
    }

    /**
     * Rewrites the guest's /etc/resolv.conf for the current network; with [proxied], the guest
     * asks [DnsProxy] (Burrow redirects its address).
     */
    fun writeResolvConf(context: Context, rootfs: File, proxied: Boolean) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val content = buildString {
            appendLine("# Written by DroidNux from the Android network settings.")
            if (proxied) appendLine("nameserver ${DnsProxy.GUEST_NAMESERVER}")
            else dnsServers(context).forEach { appendLine("nameserver $it") }
        }
        val file = File(etc, "resolv.conf")
        // Never write through a symlink planted in the rootfs.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
        file.writeText(content)
    }

    private const val MAX_NAMESERVERS = 3 // glibc ignores the rest
}
