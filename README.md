# Braid

**Braid**（编绳）是 [sing-box for Android (SFA)](https://github.com/SagerNet/sing-box-for-android)
的非官方分支：把「机场订阅」和「常驻 Tailscale」编进同一个 VPN ——
订阅、tailnet、本机三股网络拧成一根，故名 Braid。

> 本项目与 SagerNet 官方无关；核心功力全部来自
> [sing-box](https://github.com/SagerNet/sing-box) 与
> [tailscale](https://github.com/tailscale/tailscale)，感谢上游。
> 问题请优先在本仓库反馈。

适合的用户：既要用机场代理出海，又想让手机常驻自己的 tailnet
（远程访问家里/公司的设备、Taildrop 传文件、Tailscale SSH），且不想装两个 VPN 轮流开关。

## 核心特性

- **零配置注入 tailscale**：导入任意机场订阅（含旧版 DNS 语法的自动迁移），
  tailscale endpoint、路由规则（100.64/10 + fd7a::/48）、MagicDNS 全部自动合并进配置，
  换机场不用重新授权（登录态存在内部存储）。
- **分流净化**：在订阅规则前插入必须直连的净化块（局域网/保留段/国内大厂域名），
  剔除机场常见的「全局封 QUIC」「永不命中的 cn 后缀」脏规则；白名单模式
  （fakeip 命中才走代理，其余直连）可一键回退全局代理。
- **Tailscale 全家桶**：沿用上游的端点页（状态/设备列表/Taildrop/SSH/ExitNode），
  支持交互式登录（打开链接或扫码）与 auth key 填入（应用设置里改，无需重编）。
- **自建更新源**：更新清单 URL 在设置里填写，默认不内置任何服务器地址；
  配合 `scripts/publish-update.mjs` 一条命令把 APK 发到自己的机器上，应用内直接升级。
- **排障友好**：内核日志全量落盘（4MB 轮转），合并后的配置可导出，常见坑见
  [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

## 快速开始

1. 安装 APK（[Releases](../../releases) 下载，或按下面自行构建）。
2. 打开应用导入你的机场订阅（远程订阅链接或本地文件均可，旧语法自动迁移）。
3. 启动 VPN，然后登录 tailscale —— 二选一：
   - 不填 key：进「工具 → Tailscale」端点页，点打开认证链接或扫码；
   - 应用设置 → Tailscale → Auth Key：粘贴一个 **Reusable** 类型的 key
     （login.tailscale.com → Settings → Keys 生成），重启服务生效。
4. （可选）应用设置 → 更新设置 → 自建更新源：填你自己的清单 URL。

## 构建

### 1. 编 libbox.aar（改动 Go 侧或升级内核时才需要）

依赖 Go ≥1.26、JDK 17、Android SDK/NDK。sing-box 源码与 AAR 必须同源
（SFA master 用了很多新版 libbox 的 API，拿旧 AAR 凑会编译失败）：

```bash
go run ./cmd/internal/build_libbox -target android -platform android/arm64
```

产物会复制到 `app/libs/`。详见上游仓库的 `build_libbox` 文档。

### 2. 编 APK

```bash
./gradlew :app:assembleOtherDebug --no-daemon
# 产物：app/build-out/outputs/apk/other/debug/Braid-*-arm64-v8a-debug.apk
```

`versionCode` 用「epoch 秒 / 10」自动生成，天然单调递增。

## 自建更新源（可选）

```bash
python -m http.server 8088 --directory <UPDATE_DIR>   # 任意静态服务都行
node scripts/publish-update.mjs <apk路径> "更新说明"     # 生成 version.json + 拷贝 APK
```

手机端「应用设置 → 更新设置 → 自建更新源」填
`http://<你的机器>:8088/braid/version.json`。
明文 HTTP 需要在 `app/src/main/res/xml/network_security_config.xml`
为你的主机加一行放行（`ts.net` 已内置）。APK 完整性由 Android 签名校验兜底。

## 与上游的差异

完整说明见提交历史；摘要：

| 模块 | 改动 |
|---|---|
| `bg/TsConfigMerger.kt`（新增） | 订阅 + tailscale 配置合并的核心：DNS 语法迁移、endpoint/路由/DNS 注入、规则净化、白名单分流、注入失败逐级降级 |
| `bg/BoxService.kt` | 配置加载点注入合并、内核日志落盘、日志缓冲写入 |
| `bg/VPNService.kt` | tun 默认不承载 IPv6（保留 tailnet 段）、旧系统分支同策略 |
| `update/TailnetUpdater.kt`（新增） | 自建更新清单检查（URL 由用户配置，默认停用） |
| 应用设置 | Tailscale Auth Key、自建更新源两个填入栏 |
| 资源 | 应用更名 Braid、自绘编绳图标（`scripts/make_icon.mjs`） |

## 许可证

本项目遵循上游的 [GNU GPL-3.0](LICENSE)。
基于 [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)
与 [SagerNet/sing-box](https://github.com/SagerNet/sing-box) 修改；
包含 [tailscale](https://github.com/tailscale/tailscale)（BSD-3-Clause）。
