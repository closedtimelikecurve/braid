# 排障笔记

来自长期真机使用的实战记录，按出现频率排列。排障前先在
「应用设置 → 打开内核日志落盘」确认 `files/singbox.log` 在写字
（界面日志是环形缓冲，排内核问题不够用；本分支把全量内核日志同步落盘，
上限 4MB 自动轮转，`adb shell run-as <包名> cat files/singbox.log` 直接拉）。

## 网络类

1. **公共 WiFi 的「选择性 TCP 黑洞」会伪装成应用故障**。
   某些开放 WiFi 上 ICMP/DNS/UDP 全通、系统显示已联网，但 TCP 443 到部分
   CDN 段 100% SYN 超时，代理路径偶尔也中招。症状恰好像「某 App 的评论区
   /图片加载不出来」。先看日志里 `dial wlan0 ... i/o timeout` 的目标段和占比，
   再换蜂窝网络 A/B，别急着怀疑配置。

2. **用「带 detour 的远程 DNS」解析代理自己的域名 = 死锁**。
   解析 controlplane → 交给 remote(1.1.1.1 via 代理) → 要先连代理 →
   代理域名又要解析 → `DNS query loopback in transport[remote]`。
   代理的上游解析必须走**直连** DNS（合并器固定注入独立的直连 resolver）。

3. **tailscale endpoint 的 dialer 不经过 route 规则**。
   控制面不加 `detour` 就是裸连，被墙网络下表现为 TLS `EOF`，节点永远 offline。
   合并器会自动把订阅里的代理选择器填进 endpoint 的 detour。

4. **AAAA 黑洞**：手机没有可用 IPv6 而 DNS 返回 AAAA 时，App 优先连 v6 直连，
   报 `connect: network is unreachable`。已用 `dns.strategy = ipv4_only` 缓解。

5. **光禁 AAAA 不够，App 会缓存 v6 地址**：真正的解法是让 tun 不承载 `::/0`
   （v6 地址照加、路由不加），connect 立刻 ENETUNREACH，App 才会降级到 IPv4。
   tailnet 的 `fd7a:115c:a1e0::/48` 例外，始终放行。

6. **机场的全局 `udp/443 → REJECT` 是头号杀手**：QUIC 被本地 REJECT 后表现为
   「连接建立但上下行为零」。合并器只对命中 fakeip 的目标保留断流，直连流量放行。

7. **AAAA/私网/国内大厂域名净化**：合并器在订阅规则前插入必须直连的净化块
   （保留段、`.local` 等本地域名、常见国内大厂域名），并剔除订阅里
   无条件封 QUIC、永不命中的 `domain_suffix:["cn"]` 两条脏规则。

## tailscale 类

8. **auth key 必须用 Reusable**：内核每次重启都会拿它重登；一次性 key 首登即消耗，
   重启后卡在「需要登录」。key 可以在「应用设置 → Tailscale」里填，
   或写进 `files/tailscale_auth_key.txt`；都不填则走交互式登录（端点页扫码/开链接）。

9. **state_directory 必须在内部存储**：tailscaled 要在那里建 Unix socket，
   外部存储不支持，表现为一直卡在「正在初始化」。

10. **重启/切配置后节点 offline**：历史上两个叠加原因 —— DNS 自举死锁（见 #2）
    和控制面没走代理（见 #3）。两者都已由合并器自动处理。

## 构建类

11. **libbox.aar 与源码必须同源**：SFA master 用了很多新版 libbox 的 API，
    拿旧 AAR 凑会编译失败。改了 Go 侧或升级内核后要重编 AAR。

12. **vivo 等国产 ROM 覆盖安装**：versionCode 必须变大，且 VPN 前台服务必须已停
    （`adb shell am force-stop` 先执行），否则 `INSTALL_FAILED_ABORTED`。

13. **`app/build/outputs` 目录被系统进程锁死删不掉**：本仓库已把构建目录改到
    `build-out`（`app/build.gradle.kts` 里的 `layout.buildDirectory`）。

## 更新类

14. **只有 VPN 运行时才能检查自建更新源**：tailscale 端点在 VPN 内核里，
    VPN 没起来就没有 tailnet 路由，检查会静默失败。
15. **VPN 刚起来的头几秒应用内请求可能被 Connection reset**：tailscale 端点
    还没建稳，重试机制（5×8s）就是为这个准备的。
16. **versionCode 用「epoch 秒 / 10」**：天然单调递增，同一分钟内连续构建也能
    覆盖安装，不会绕回。
