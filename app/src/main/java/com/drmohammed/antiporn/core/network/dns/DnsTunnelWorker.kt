package com.drmohammed.antiporn.core.network.dns

import android.os.ParcelFileDescriptor
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Worker coroutine loop that handles reading, policy evaluation, local synthesis,
 * and upstream forwarding for DNS packets traversing the local TUN interface.
 */
class DnsTunnelWorker(
    private val forwarder: DnsUpstreamForwarder,
    private val policyProvider: () -> DevicePolicyBundle,
    private val customBlockedProvider: () -> Set<String>,
    private val isRunningProvider: () -> Boolean
) {

    companion object {
        private const val TAG = "AntiPorn_DnsWorker"
        private const val PACKET_BUFFER_SIZE = 32767
    }

    private val outStreamLock = Any()

    fun start(scope: CoroutineScope, pfd: ParcelFileDescriptor): Job {
        return scope.launch(Dispatchers.IO) {
            coroutineScope {
                var inStream: FileInputStream? = null
                var outStream: FileOutputStream? = null
                val packetBuffer = ByteArray(PACKET_BUFFER_SIZE)

                Timber.tag(TAG).i("DNS Filtering Loop active on TUN interface (24/7 Defense)")

                try {
                    inStream = FileInputStream(pfd.fileDescriptor)
                    outStream = FileOutputStream(pfd.fileDescriptor)

                    while (isActive && isRunningProvider()) {
                        val bytesRead = try {
                            inStream.read(packetBuffer)
                        } catch (e: IOException) {
                            Timber.tag(TAG).d("TUN read IOException: ${e.message}")
                            break // Interface closed or handover initiated
                        }
                        if (bytesRead < 0) {
                            Timber.tag(TAG).d("TUN interface EOF reached; breaking read loop")
                            break
                        }
                        if (bytesRead == 0) continue

                        val version = (packetBuffer[0].toInt() and 0xFF) ushr 4
                        Timber.tag(TAG).d("TUN packet read: $bytesRead bytes, ipVersion=$version")

                        if (version == 6) {
                            val ipv6Refused = DnsPacketParser.buildIpv6RefusedResponse(packetBuffer, bytesRead)
                            if (ipv6Refused != null) {
                                Timber.tag(TAG).i("Instant REFUSED sent for IPv6 DNS query -> forces fast IPv4 fallback")
                                writeToTun(outStream, ipv6Refused)
                            }
                            continue
                        }

                        val parsedQuery = DnsPacketParser.parseDnsQuery(packetBuffer, bytesRead)
                        if (parsedQuery == null) {
                            Timber.tag(TAG).d("Non-DNS or unparsed packet received on TUN (version=$version, len=$bytesRead)")
                            continue
                        }
                        Timber.tag(TAG).i("DNS Query: ${parsedQuery.qName} (type=${parsedQuery.qType}) from ${parsedQuery.srcPort}")
                        val policy = policyProvider()
                        val customBlocked = customBlockedProvider()
                        val action = DnsPolicyEvaluator.evaluate(parsedQuery, policy, customBlocked)

                        when (action) {
                            is DnsPolicyEvaluator.PolicyAction.BlockPorn,
                            is DnsPolicyEvaluator.PolicyAction.BlockAds,
                            is DnsPolicyEvaluator.PolicyAction.BlockTelegramWeb,
                            is DnsPolicyEvaluator.PolicyAction.BlockCustom,
                            is DnsPolicyEvaluator.PolicyAction.BlockDohBypass -> {
                                val nxResponse = DnsPacketParser.buildNxDomainResponse(parsedQuery)
                                writeToTun(outStream, nxResponse)
                            }
                            is DnsPolicyEvaluator.PolicyAction.SynthesizeYouTubeRestricted -> {
                                val vipResponse = DnsPacketParser.buildSafeSearchVipResponse(parsedQuery)
                                writeToTun(outStream, vipResponse)
                            }
                            is DnsPolicyEvaluator.PolicyAction.Pass -> {
                                launch(Dispatchers.IO) {
                                    try {
                                        val replyPayload = forwarder.resolve(
                                            qName = parsedQuery.qName,
                                            qType = parsedQuery.qType,
                                            transactionId = parsedQuery.transactionId,
                                            rawDnsPayload = parsedQuery.rawDnsPayload
                                        )
                                        if (replyPayload != null && isActive && isRunningProvider()) {
                                            val clientPacket = DnsPacketParser.wrapUpstreamDnsResponse(
                                                clientIp = parsedQuery.srcIp,
                                                serverIp = parsedQuery.dstIp,
                                                clientPort = parsedQuery.srcPort,
                                                serverPort = parsedQuery.dstPort,
                                                dnsResponsePayload = replyPayload
                                            )
                                            writeToTun(outStream, clientPacket)
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag(TAG).w("Upstream DNS query failed for ${parsedQuery.qName}: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).d("DNS Filtering Loop terminated: ${e.message}")
                } finally {
                    try { inStream?.close() } catch (_: Exception) {}
                    try { outStream?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun writeToTun(outStream: FileOutputStream, packet: ByteArray) {
        synchronized(outStreamLock) {
            try {
                outStream.write(packet)
                Timber.tag(TAG).d("Sent response packet to TUN (${packet.size} bytes)")
            } catch (e: IOException) {
                Timber.tag(TAG).w(e, "Failed writing DNS response packet to TUN")
            }
        }
    }
}
