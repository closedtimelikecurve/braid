package io.nekohasekai.sfa.bg

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.toIpPrefix
import io.nekohasekai.sfa.ktx.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class VPNService :
    VpnService(),
    PlatformInterfaceWrapper {
    companion object {
        private const val TAG = "VPNService"

        /**
         * 是否让 tun 承载 IPv6。
         *
         * 关着 —— 手机侧经常没有可用 IPv6，而 App（抖音等）会缓存 v6 地址并优先连接。
         * tun 一旦有 v6 地址，这些 connect 会「先进入 tun、再在出站时静默失败」，
         * App 认为地址没死、一直重试，表现就是视频/图片永远加载不出来。
         * 不宣告 v6，connect 立刻 ENETUNREACH，App 才会正常降级到 IPv4。
         *
         * 注意：tailnet 的 fd7a::/48 走的是 tailscale endpoint 自身的用户态，
         * 不依赖 tun 的 v6，关掉不影响 tailscale 连接。
         */
        private const val ENABLE_TUN_IPV6 = false

    }

    private val service = BoxService(this, this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind()
    }

    override fun onDestroy() {
        service.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder =
            Builder()
                .setSession("sing-box")
                .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (Settings.allowBypass) {
            builder.allowBypass()
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        // v6 地址必须照加：sing-tun 的栈会在这个地址上 bind 监听，
        // 少了它会直接 `listen tcp6 ...: bind: cannot assign requested address` 起不来。
        // 「不让 v6 流量进 tun」是靠在下面跳过 ::/0 路由实现的，见 ENABLE_TUN_IPV6。
        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServerAddress = options.dnsServerAddress
                while (dnsServerAddress.hasNext()) {
                    builder.addDnsServer(dnsServerAddress.next())
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
                }

                // tailnet 的 v6 段始终放进 tun：tailscale 用 fd7a:115c:a1e0::/48，
                // 放行它既能用 v6 访问 tailnet 设备，又不会让其它 App 拿到
                // 「有路由但出不去」的 IPv6（那是抖音刷不出视频的根因）。
                // 注意 Builder.addRoute 收的是「地址 + 前缀长度」，地址里不能带 "/48"。
                builder.addRoute("fd7a:115c:a1e0::", 48)

                if (ENABLE_TUN_IPV6) {
                    val inet6RouteAddress = options.inet6RouteAddress
                    if (inet6RouteAddress.hasNext()) {
                        while (inet6RouteAddress.hasNext()) {
                            builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                        }
                    } else if (options.inet6Address.hasNext()) {
                        builder.addRoute("::", 0)
                    }

                    val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                    while (inet6RouteExcludeAddress.hasNext()) {
                        builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
                    }
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }

                // 与上面的 TIRAMISU 分支同策略：默认不让 tun 承载 v6，
                // 否则 App 缓存的 v6 地址会「进 tun 再静默失败」；只放行 tailnet 段。
                if (ENABLE_TUN_IPV6) {
                    val inet6RouteAddress = options.inet6RouteRange
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                } else {
                    builder.addRoute("fd7a:115c:a1e0::", 48)
                }
            }

            val includePackage = options.includePackage
            if (includePackage.hasNext()) {
                while (includePackage.hasNext()) {
                    try {
                        val nextPackage = includePackage.next()
                        builder.addAllowedApplication(nextPackage)
                        Log.d("VPNService", "addAllowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e("VPNService", "addAllowedApplication failed", e)
                    }
                }
            }

            val excludePackage = options.excludePackage
            if (excludePackage.hasNext()) {
                while (excludePackage.hasNext()) {
                    try {
                        val nextPackage = excludePackage.next()
                        builder.addDisallowedApplication(nextPackage)
                        Log.d("VPNService", "addDisallowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e("VPNService", "addDisallowedApplication failed", e)
                    }
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            systemProxyEnabled = Settings.systemProxyEnabled
            if (systemProxyEnabled) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        options.httpProxyServer,
                        options.httpProxyServerPort,
                        options.httpProxyBypassDomain.toList(),
                    ),
                )
            }
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        service.fileDescriptor = pfd
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)

    override fun cancelNotification(identifier: String, typeID: Int) = service.cancelNotification(identifier, typeID)
}
