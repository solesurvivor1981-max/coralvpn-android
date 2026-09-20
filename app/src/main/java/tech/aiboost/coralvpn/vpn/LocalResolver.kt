package tech.aiboost.coralvpn.vpn

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/**
 * Platform "local" DNS transport for libbox. The server config declares a DNS server of
 * `type: "local"`; without this the core dereferences a null transport and crashes on start.
 *
 * Resolves on the UNDERLYING network (DefaultNetworkMonitor.defaultNetwork) so DNS never
 * loops back into the tun. Ported from SFA's LocalResolver; verified against sing-box
 * v1.15.0-alpha.6 experimental/libbox/dns.go. The core calls lookup/exchange on a native
 * worker thread, so runBlocking here is the intended synchronous contract.
 */
object LocalResolver : LocalDNSTransport {

    private const val RCODE_NXDOMAIN = 3

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val defaultNetwork = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        return runBlocking {
            suspendCoroutine { continuation ->
                val signal = CancellationSignal()
                ctx.onCancel {
                    signal.cancel()
                    continuation.tryResumeWithException(CancellationException())
                }
                val callback = object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                        continuation.tryResume(Unit)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        when (val cause = error.cause) {
                            is ErrnoException -> {
                                ctx.errnoCode(cause.errno)
                                continuation.tryResume(Unit)
                                return
                            }
                        }
                        continuation.tryResumeWithException(error)
                    }
                }
                DnsResolver.getInstance().rawQuery(
                    defaultNetwork,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback,
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNetwork = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        return runBlocking {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suspendCoroutine { continuation ->
                    val signal = CancellationSignal()
                    ctx.onCancel {
                        signal.cancel()
                        continuation.tryResumeWithException(CancellationException())
                    }
                    val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                        override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                            if (rcode == 0) {
                                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                            } else {
                                ctx.errorCode(rcode)
                            }
                            continuation.tryResume(Unit)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            when (val cause = error.cause) {
                                is ErrnoException -> {
                                    ctx.errnoCode(cause.errno)
                                    continuation.tryResume(Unit)
                                    return
                                }
                            }
                            continuation.tryResumeWithException(error)
                        }
                    }
                    val type = when {
                        network.endsWith("4") -> DnsResolver.TYPE_A
                        network.endsWith("6") -> DnsResolver.TYPE_AAAA
                        else -> null
                    }
                    if (type != null) {
                        DnsResolver.getInstance().query(
                            defaultNetwork, domain, type,
                            DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal, callback,
                        )
                    } else {
                        DnsResolver.getInstance().query(
                            defaultNetwork, domain,
                            DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal, callback,
                        )
                    }
                }
            } else {
                val answer = try {
                    defaultNetwork.getAllByName(domain)
                } catch (e: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                    return@runBlocking
                }
                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
            }
        }
    }

    private fun <T> Continuation<T>.tryResume(value: T) {
        try {
            resumeWith(Result.success(value))
        } catch (ignored: IllegalStateException) {
        }
    }

    private fun <T> Continuation<T>.tryResumeWithException(exception: Throwable) {
        try {
            resumeWith(Result.failure(exception))
        } catch (ignored: IllegalStateException) {
        }
    }
}
