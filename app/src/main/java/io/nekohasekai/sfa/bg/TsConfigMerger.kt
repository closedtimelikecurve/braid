package io.nekohasekai.sfa.bg

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import java.io.File
import java.net.URI

/**
 * 把 tailscale 作为「客户端级常驻组件」合进任意机场订阅。
 *
 * 这是整个 App 的核心：订阅只提供节点和分流，tailscale 段由这里注入，
 * 所以换多少次机场都不影响 tailnet 连接（登录状态在 state_directory 里）。
 *
 * 合并时会顺带处理两件必须做的事：
 *  1. 旧版 DNS 语法迁移（1.12 弃用、1.14 移除），否则新版内核直接拒绝加载
 *  2. 补 auto_detect_interface / override_android_vpn，否则内核自己的出站
 *     会被自己的 tun 接管，表现为所有连接 i/o timeout
 */
object TsConfigMerger {

    const val TS_TAG = "ts"
    const val TS_DNS_TAG = "ts-dns"
    private const val RESOLVER_TAG = "ts-resolver"

    private const val TAILNET_V4 = "100.64.0.0/10"
    private const val TAILNET_V6 = "fd7a:115c:a1e0::/48"

    /** 默认解析器：不依赖代理是否就绪，实测能解析 controlplane.tailscale.com */
    private const val FALLBACK_DNS = "223.5.5.5"

    private val gson = Gson()

    data class Result(
        val json: String,
        val outboundCount: Int,
        val proxyTag: String?,
        val authKeyPresent: Boolean,
    )

    /**
     * tailscale auth key 的兜底常量。**刻意留空**：
     * 早期版本把 key 硬编码在这里，一是泄露面大（该 key 已暴露过，建议 revoke），
     * 二是推广给别的用户时不能内置开发者的 key。
     */
    const val TS_AUTH_KEY = ""

    /**
     * tailscale auth key 三级优先级：
     *  1. 前端「应用设置 → Tailscale → Auth Key」（存 DataStore，普通用户入口）
     *  2. 内部存储 `files/tailscale_auth_key.txt`（adb 高级用法，便于脚本轮换：
     *     `adb shell run-as io.nekohasekai.sfa.ts sh -c 'echo ... > files/tailscale_auth_key.txt'`）
     *  3. 编译期常量 [TS_AUTH_KEY]（默认空）
     *
     * 都为空时 endpoint 不带 auth_key，走交互式登录：
     * 端点页会出现 authURL，打开链接或扫码即可，见 TailscaleEndpointScreen。
     */
    fun currentAuthKey(): String {
        val fromSettings = runCatching { Settings.tsAuthKey.trim() }.getOrNull()
        if (!fromSettings.isNullOrBlank()) return fromSettings
        val file = File(Application.application.filesDir, "tailscale_auth_key.txt")
        val fromFile = runCatching { file.readText().trim() }.getOrNull()
        if (!fromFile.isNullOrBlank()) return fromFile
        return TS_AUTH_KEY
    }

    /** 供 SFA 调用：任意订阅内容 → 带 tailscale 的配置 */
    fun mergeForBox(raw: String, stateDirectory: String? = null): String =
        merge(raw, currentAuthKey(), stateDirectory).json

    /**
     * 任意订阅 → 内核一定能加载的配置，注入失败时逐级降级：
     *   完整合并 → 仅语法迁移 → 原文。
     *
     * 直接回退原文是最坏选择：旧语法订阅连内核校验都过不去，整个 VPN 起不来。
     * 中间留一层「只迁移不注入」，保证代理至少可用 —— tailnet 暂时缺席
     * 好过 VPN 完全离线，日志里能看到降级原因再排查。
     */
    fun safeMergeForBox(raw: String, stateDirectory: String, log: (String) -> Unit = {}): String {
        if (raw.isBlank()) return raw
        runCatching { return mergeForBox(raw, stateDirectory) }
            .onFailure { log("tailscale 注入失败，降级为仅语法迁移: ${it.message}") }
        runCatching { return migrateForProfile(raw) }
            .onFailure { log("语法迁移也失败，回退原始配置: ${it.message}") }
        return raw
    }

    /**
     * 只做语法迁移，不注入 tailscale。
     * SFA 在导入/更新远程订阅时会先 Libbox.checkConfig，而机场订阅普遍是
     * 1.11 时代的旧 DNS 写法（1.14 已移除），不迁移就导不进来。
     */
    fun migrateForProfile(raw: String): String = runCatching {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return raw
        migrateDns(root, addFallbackResolver = false)
        gson.toJson(root)
    }.getOrElse { raw }

    fun merge(raw: String, authKey: String, stateDirectory: String? = null): Result {
        val root = gson.fromJson(raw, JsonObject::class.java)
            ?: error("订阅内容不是合法 JSON 对象")

        // 节点 tag 撞名会让路由规则指向错误目标，提前报出来
        root.getAsJsonArray("outbounds")?.forEach { el ->
            val tag = el.asJsonObject.get("tag")?.asString
            require(tag != TS_TAG) { "订阅里已有名为 $TS_TAG 的节点，无法注入 tailscale" }
        }

        val proxyTag = pickProxyTag(root)
        val defaultResolver = migrateDns(root)
        injectEndpoint(root, authKey, defaultResolver, stateDirectory, proxyTag)
        injectRouteRules(root, defaultResolver)
        injectDnsRules(root, defaultResolver)

        val out = gson.toJson(root)
        require(!out.contains("_meta")) { "配置里混入了非 sing-box 字段" }
        return Result(
            json = out,
            outboundCount = root.getAsJsonArray("outbounds")?.size() ?: 0,
            proxyTag = proxyTag,
            authKeyPresent = authKey.isNotBlank(),
        )
    }

    // ---------------------------------------------------------------- endpoint

    private fun injectEndpoint(
        root: JsonObject,
        authKey: String,
        resolver: String,
        stateDirectory: String?,
        proxyTag: String?,
    ) {
        val ep = JsonObject().apply {
            addProperty("type", "tailscale")
            addProperty("tag", TS_TAG)
            // 必须是内部存储的绝对路径：tailscaled 要在这里建 Unix socket，
            // 外部存储（SFA 的 workingDir 在 /sdcard/Android/data）不支持，会卡在初始化
            addProperty("state_directory", stateDirectory ?: "tailscale")
            addProperty("domain_resolver", resolver)
            // ⚠️ 必须显式走代理。endpoint 自己的 dialer 是**直连**的，不经过 route 规则，
            // 所以控制面（controlplane.tailscale.com）会裸连 → 在国内被 RST，
            // 日志表现为 `fetch control key: ... EOF`，节点永远 offline。
            if (proxyTag != null) addProperty("detour", proxyTag)
            if (authKey.isNotBlank()) addProperty("auth_key", authKey)
        }
        val endpoints = root.getAsJsonArray("endpoints") ?: JsonArray().also {
            root.add("endpoints", it)
        }
        // 去掉可能残留的同名 endpoint，避免重复定义
        val cleaned = JsonArray()
        endpoints.forEach { el ->
            if (el.asJsonObject.get("tag")?.asString != TS_TAG) cleaned.add(el)
        }
        cleaned.add(ep)
        root.add("endpoints", cleaned)
    }

    // ------------------------------------------------------------------ route

    private fun injectRouteRules(root: JsonObject, resolver: String) {
        val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
        route.addProperty("auto_detect_interface", true)
        route.addProperty("override_android_vpn", true)
        if (!route.has("default_domain_resolver")) {
            route.addProperty("default_domain_resolver", resolver)
        }

        val old = route.getAsJsonArray("rules") ?: JsonArray()
        val out = JsonArray()

        // 开头这些动作类规则必须留在最前，否则域名嗅探和 DNS 劫持会失效
        var cursor = 0
        while (cursor < old.size()) {
            val action = old[cursor].asJsonObject.get("action")?.asString
            if (action in ACTIONS_KEEP_FIRST) {
                out.add(old[cursor])
                cursor++
            } else {
                break
            }
        }
        // 订阅里没写就必须补，否则 DNS 查询会被当普通 UDP 转给 tun 内地址，全盘解析失败
        if (!hasAction(out, "hijack-dns")) {
            out.add(JsonObject().apply { addProperty("action", "sniff") })
            out.add(JsonObject().apply {
                addProperty("protocol", "dns")
                addProperty("action", "hijack-dns")
            })
        }

        // tailnet 流量必须在这里就分流出去，晚了会被订阅的「全局代理」规则吃掉
        out.add(JsonObject().apply {
            add("ip_cidr", JsonArray().apply {
                add(TAILNET_V4)
                add(TAILNET_V6)
            })
            addProperty("action", "route")
            addProperty("outbound", TS_TAG)
        })
        out.add(JsonObject().apply {
            addProperty("preferred_by", TS_TAG)
            addProperty("action", "route")
            addProperty("outbound", TS_TAG)
        })

        // ---------------- 净化块：这几类流量永远直连，不能让订阅规则吃掉 ----------------
        // 局域网 / 私网
        out.add(directRule { addProperty("ip_is_private", true) })
        // 保留段 + 广播/多播/链路本地，订阅的 ip_is_private 不覆盖这些
        out.add(directRule { add("ip_cidr", stringArray(RESERVED_CIDR)) })
        // .local(mDNS) / .lan / .home.arpa 这类本地域名，投屏、打印机、智能家居靠它
        out.add(directRule { add("domain_suffix", stringArray(LOCAL_DOMAINS)) })
        // 国内大厂核心域名强制直连：机场的被墙名单偶尔会把它们的 CDN/API 也塞进去，
        // 一旦走境外节点就是「能连上但零流量」。这里做硬保证，不让名单决定国内 App 死活。
        out.add(directRule { add("domain_suffix", stringArray(CN_DIRECT_SUFFIX)) })

        // QUIC：订阅普遍无脑 `udp/443 -> REJECT` 全局封杀，会打断所有 App 的 HTTP/3。
        // 只对「走代理的目标」断流（即命中 fakeip 的域名），直连流量的 QUIC 一律放行，
        // 视频/游戏/App 内 WebView 就不会再莫名卡首帧。
        val fakeipRanges = fakeipRanges(root)
        if (fakeipRanges.isNotEmpty()) {
            out.add(JsonObject().apply {
                add("ip_cidr", stringArray(fakeipRanges))
                addProperty("port", 443)
                addProperty("network", "udp")
                addProperty("action", "route")
                addProperty("outbound", "REJECT")
            })
        }

        // 订阅规则原样保留，只剔掉两条有害的
        while (cursor < old.size()) {
            val rule = old[cursor].asJsonObject
            if (!isGlobalQuicBlock(rule) && !isBareCnSuffix(rule)) out.add(rule)
            cursor++
        }

        // ---------------- 白名单模式 ----------------
        // 订阅原来的语义是「没命中 → 一律翻墙」（final = 漏网之鱼），结果是 IP 直连的
        // App（游戏服务器、长连接、政务/银行接口）全被绕道国外，延迟高还会被风控。
        //
        // 反过来做：命中 fakeip 的目标 = 机场自己的「被墙域名表」，只有它们走代理；
        // 其余一律直连。等价于「国内 App 完全不感知代理」，出事时 UI 里切
        // 「全局代理」还能一键兜底。
        if (WHITELIST_MODE && fakeipRanges.isNotEmpty()) {
            val proxyTarget = route.get("final")?.asString
            if (!proxyTarget.isNullOrBlank() && proxyTarget != "DIRECT") {
                out.add(JsonObject().apply {
                    add("ip_cidr", stringArray(fakeipRanges))
                    addProperty("action", "route")
                    addProperty("outbound", proxyTarget)
                })
                route.addProperty("final", "DIRECT")
            }
        }
        route.add("rules", out)
    }

    private fun directRule(block: JsonObject.() -> Unit): JsonObject = JsonObject().apply {
        addProperty("action", "route")
        addProperty("outbound", "DIRECT")
        block()
    }

    private fun stringArray(items: List<String>) = JsonArray().apply { items.forEach { add(it) } }

    /** `{"port":443,"network":"udp","outbound":"REJECT"}` 这种不带任何条件的全局 QUIC 封锁 */
    private fun isGlobalQuicBlock(o: JsonObject): Boolean {
        if (o.get("outbound")?.asString != "REJECT") return false
        if (o.get("network")?.asString != "udp") return false
        if (o.get("port")?.asString?.toIntOrNull() != 443) return false
        return !o.has("domain") && !o.has("domain_suffix") && !o.has("ip_cidr") && !o.has("rule_set")
    }

    /** 单条 `domain_suffix:["cn"] -> DIRECT` 的粗暴兜底，排在最后根本命中不了，删掉更干净 */
    private fun isBareCnSuffix(o: JsonObject): Boolean {
        val suffixes = o.getAsJsonArray("domain_suffix") ?: return false
        if (suffixes.size() != 1 || suffixes[0].asString != "cn") return false
        return !o.has("domain") && !o.has("domain_keyword") && !o.has("ip_cidr") && !o.has("rule_set")
    }

    /** 从 dns.servers 里取 fakeip 的地址段，用来界定「哪些目标算走代理」 */
    private fun fakeipRanges(root: JsonObject): List<String> {
        val servers = root.getAsJsonObject("dns")?.getAsJsonArray("servers") ?: return emptyList()
        val ranges = mutableListOf<String>()
        servers.forEach { el ->
            val o = el.asJsonObject
            if (o.get("type")?.asString != "fakeip") return@forEach
            listOf("inet4_range", "inet6_range").forEach { key ->
                o.get(key)?.asString?.let { ranges.add(it) }
            }
        }
        return ranges
    }

    private val RESERVED_CIDR = listOf(
        "0.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16", "224.0.0.0/4", "240.0.0.0/4",
        "::1/128", "fe80::/10", "fc00::/7", "ff00::/8",
    )

    private val LOCAL_DOMAINS = listOf(
        "localhost", "local", "lan", "home.arpa", "internal", "invalid", "test", "mshome.net",
    )

    /**
     * 国内大厂域名，永远直连。
     *
     * 现实教训：机场的 fakeip「被墙名单」并不干净，会混入国内服务的 API/CDN 域名。
     * 抖音的 `api5-normal-lf.amemv.com` 就被 UDP/443 的全局 QUIC 规则拒掉过，
     * 表现是「连接列表里一堆上下行为零的抖音进程、视频刷不出来」。
     * 这类核心域名不该交给机场名单去赌，这里硬编码直连。
     */
    private val CN_DIRECT_SUFFIX = listOf(
        // 字节系（国内产品；tiktok 系列不在此列，那些本来就要代理）
        "amemv.com", "douyin.com", "douyinvod.com", "douyinpic.com", "iesdouyin.com",
        "snssdk.com", "pstatp.com", "toutiao.com", "ixigua.com", "bytecdn.cn",
        "feishu.cn", "feishu.net", "larksuite.com",
        // 腾讯
        "qq.com", "gtimg.cn", "qpic.cn", "myqcloud.com", "tencent.com", "tencentcloud.com",
        // 阿里
        "taobao.com", "tmall.com", "alipay.com", "alicdn.com", "aliyuncs.com",
        "aliyun.com", "dingtalk.com", "alibaba.com",
        // 百度 / 京东 / 拼多多 / 美团
        "baidu.com", "bdstatic.com", "jd.com", "360buyimg.com", "yangkeduo.com",
        "pinduoduo.com", "meituan.com", "meituan.net", "dianping.com",
        // 短视频 / 社区
        "bilibili.com", "bilivideo.com", "hdslb.com", "kuaishou.com", "gifshow.com",
        "xiaohongshu.com", "xhscdn.com", "weibo.com", "sinaimg.cn", "zhihu.com", "zhimg.com",
        // 网易 / 米哈游 / 设备厂商
        "163.com", "126.net", "netease.com", "mihoyo.com", "yuanshen.com",
        "huawei.com", "hicloud.com", "xiaomi.com", "mi.com", "oppo.com", "vivo.com",
        // 出行 / 支付 / 政务民生
        "12306.cn", "amap.com", "didiglobal.com", "ctrip.com", "qunar.com",
        "unionpay.com", "gov.cn",
    )

    /** true = 白名单模式（只有被墙域名走代理）；false = 恢复订阅原本的「未知即代理」 */
    private const val WHITELIST_MODE = true

    private val ACTIONS_KEEP_FIRST = setOf("sniff", "hijack-dns", "resolve")

    private fun hasAction(rules: JsonArray, action: String): Boolean {
        for (i in 0 until rules.size()) {
            if (rules[i].asJsonObject.get("action")?.asString == action) return true
        }
        return false
    }

    // -------------------------------------------------------------------- dns

    /**
     * 迁移旧版 DNS 语法并返回可用的默认解析器 tag。
     *
     * 1.12 起 `{"address": "fakeip"}` 这种写法被弃用，1.13/1.14 直接移除；
     * 而机场订阅大多是 1.11 时代的产物，不迁移就加载失败。
     */
    private fun migrateDns(root: JsonObject, addFallbackResolver: Boolean = true): String {
        val dns = root.getAsJsonObject("dns") ?: JsonObject().also { root.add("dns", it) }

        // 只解析 A 记录：手机 Wi-Fi/蜂窝经常没有可用 IPv6，但 DNS 照样返回 AAAA，
        // App 会优先连 IPv6 → 直连时报
        //   dial tcp [2409:...::166]:443: connect: network is unreachable
        // 表现就是「能连上、但视频/图片加载不出来」，而且日志里全是这种 ERROR。
        if (!dns.has("strategy")) {
            dns.addProperty("strategy", "ipv4_only")
        }

        // 顶层 fakeip 配置块是 1.14 移除的，里面的地址段要搬进 fakeip server
        val legacyFakeip = dns.remove("fakeip") as? JsonObject

        val servers = dns.getAsJsonArray("servers") ?: JsonArray().also { dns.add("servers", it) }
        val migrated = JsonArray()
        servers.forEach { el ->
            migrated.add(migrateServer(el, legacyFakeip))
        }

        dns.add("servers", migrated)

        if (!addFallbackResolver) {
            // 导入阶段只做迁移，不往用户的配置里塞额外 DNS
            return migrated.firstOrNull()
                ?.asJsonObject?.get("tag")?.asString ?: "local"
        }

        // 不能复用订阅里的解析器：机场的「国内」DNS（cn）会污染
        // controlplane.tailscale.com 的解析结果，导致 endpoint 起不来。
        // 固定用独立的公共解析器，与订阅内容解耦。
        if (migrated.none { it.asJsonObject.get("tag")?.asString == RESOLVER_TAG }) {
            migrated.add(JsonObject().apply {
                addProperty("type", "udp")
                addProperty("tag", RESOLVER_TAG)
                addProperty("server", FALLBACK_DNS)
            })
        }
        dns.add("servers", migrated)
        if (!dns.has("final")) dns.addProperty("final", RESOLVER_TAG)

        // ⚠️ 这里必须用**直连**解析器（RESOLVER_TAG），不能挑订阅里带 detour 的远程 DNS。
        //
        // 曾经的写法是「优先复用带 detour 的远程 DNS」，本意是避开 DNS 污染，
        // 实际制造了自举死锁：
        //   解析 controlplane.tailscale.com
        //     → 用 remote(1.1.1.1, detour=🚀节点选择)
        //     → 要连代理，先得解析代理服务器的域名 unamecf.xxx.com
        //     → 这个域名又交给 remote 解析
        //     → 「DNS query loopback in transport[remote]」
        // 结果 endpoint 永远拿不到控制面地址，日志表现为
        //   control: TryLogin: fetch control key: ... DNS query loopback
        // 节点一直 offline，看着就像「重启后 tailscale 登录不上」。
        //
        // 实测直连 223.5.5.5 解析 controlplane.tailscale.com 得到的就是
        // 192.200.0.10x 这组真实地址，与走代理一致，没有污染，不需要绕代理。
        return RESOLVER_TAG
    }

    private fun migrateServer(el: com.google.gson.JsonElement, legacyFakeip: JsonObject?): JsonObject {
        if (!el.isJsonObject) {
            // 极老的写法只给一个 IP 字符串
            return JsonObject().apply {
                addProperty("type", "udp")
                addProperty("tag", "dns-${el.asString.hashCode()}")
                addProperty("server", el.asString)
            }
        }
        val s = el.asJsonObject
        if (s.has("type")) return s // 已经是新语法

        val addr = s.get("address")?.asString.orEmpty()
        val tag = s.get("tag")?.asString ?: "dns-${addr.hashCode()}"
        val out = JsonObject().apply { addProperty("tag", tag) }

        when {
            addr == "fakeip" -> {
                out.addProperty("type", "fakeip")
                legacyFakeip?.let { f ->
                    listOf("inet4_range", "inet6_range").forEach { k ->
                        f.get(k)?.let { out.add(k, it) }
                    }
                }
            }

            addr == "local" -> out.addProperty("type", "local")

            addr.startsWith("dhcp://") -> {
                out.addProperty("type", "dhcp")
                out.addProperty("interface", addr.removePrefix("dhcp://"))
            }

            addr.contains("://") -> {
                val scheme = addr.substringBefore("://")
                out.addProperty("type", scheme)
                runCatching {
                    val uri = URI(addr)
                    uri.host?.let { out.addProperty("server", it) }
                    if (uri.port > 0) out.addProperty("server_port", uri.port)
                    val path = uri.path
                    if (!path.isNullOrBlank() && path != "/") out.addProperty("path", path)
                }
            }

            else -> {
                out.addProperty("type", "udp")
                val (host, port) = splitHostPort(addr)
                out.addProperty("server", host)
                if (port > 0) out.addProperty("server_port", port)
            }
        }

        s.get("address_resolver")?.let { out.add("domain_resolver", it) }
        s.get("address_strategy")?.let { out.add("domain_strategy", it) }
        listOf("detour", "strategy", "client_subnet", "override_address", "override_port")
            .forEach { k -> s.get(k)?.let { out.add(k, it) } }
        return out
    }

    private fun splitHostPort(addr: String): Pair<String, Int> {
        if (addr.count { it == ':' } == 1) {
            val host = addr.substringBeforeLast(':')
            val port = addr.substringAfterLast(':').toIntOrNull() ?: 0
            if (port > 0) return host to port
        }
        return addr to 0
    }

    private fun injectDnsRules(root: JsonObject, resolver: String) {
        val dns = root.getAsJsonObject("dns")!!
        val servers = dns.getAsJsonArray("servers")!!
        if (servers.none { it.asJsonObject.get("tag")?.asString == TS_DNS_TAG }) {
            servers.add(JsonObject().apply {
                addProperty("type", "tailscale")
                addProperty("tag", TS_DNS_TAG)
                addProperty("endpoint", TS_TAG)
                addProperty("accept_default_resolvers", false)
            })
        }

        val rules = dns.getAsJsonArray("rules") ?: JsonArray().also { dns.add("rules", it) }
        if (rules.any { it.asJsonObject.get("server")?.asString == TS_DNS_TAG }) return

        val out = JsonArray()
        // ts.net 必须走 tailscale 自己的 DNS，否则 MagicDNS 名字解析不出来
        out.add(JsonObject().apply {
            add("domain_suffix", JsonArray().apply { add("ts.net") })
            addProperty("server", TS_DNS_TAG)
        })
        rules.forEach { out.add(it) }
        dns.add("rules", out)
    }

    // ------------------------------------------------------------------ utils

    private fun pickProxyTag(root: JsonObject): String? {
        val ob = root.getAsJsonArray("outbounds") ?: return null
        // 只有真实可作出站的类型才有资格当 tailscale 控制面的 detour；
        // 有些订阅会显式列出 DIRECT/REJECT outbound，选中的话控制面流量会被丢掉或直连被墙。
        val entries = ob.mapNotNull { el ->
            (el as? JsonObject)?.takeIf {
                val t = it.get("type")?.asString
                t != null && t !in NON_PROXY_TYPES && !it.get("tag")?.asString.isNullOrBlank()
            }
        }
        val tags = entries.map { it.get("tag")!!.asString }
        PREFERRED_PROXY_TAGS.firstOrNull { it in tags }?.let { return it }
        // 没有首选名就先挑代理组（跟随用户手选的节点），再不行挑第一个真实节点
        entries.firstOrNull { it.get("type")?.asString in PROXY_GROUP_TYPES }
            ?.get("tag")?.asString?.let { return it }
        tags.firstOrNull()?.let { return it }
        return root.getAsJsonObject("route")?.get("final")?.asString
            ?.takeIf { it != "DIRECT" && it != "REJECT" }
    }

    private val NON_PROXY_TYPES = setOf("direct", "block", "dns")
    private val PROXY_GROUP_TYPES = setOf("selector", "urltest")

    private val PREFERRED_PROXY_TAGS = listOf(
        "🚀节点选择", "节点选择", "🚀 节点选择", "PROXY", "Proxy", "proxy", "SELECT"
    )
}
