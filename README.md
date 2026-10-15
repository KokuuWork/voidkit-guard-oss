# VoidkitGuard —— 反作弊组件

**Minecraft 1.21.1（NeoForge / Forge）与 1.20.1（Forge）**，Voidkit 套件（`KokuuWork/voidkit`）的第 9 个组件：**客户端 Agent + 服务端校验**。
客户端与服务端拆成两个 jar：服务端依赖 `voidkit_core`，客户端**零依赖**。

<div align="center">

## 🛠 Bug 维修群

> **项目还在测试版（beta），遇到任何问题都欢迎来群里找我们。**
>
> 群名：**【MC-玩了X年服务器终于不想玩了】**

### **一键加群** → [`498700382`](https://qm.qq.com/q/ueYTU8T3xK)

[![QQ群](https://img.shields.io/badge/QQ%E7%BE%A4%20%C2%B7%20Bug%20%E7%BB%B4%E4%BF%AE%E7%BE%A4-4f46e5?style=for-the-badge&logo=tencent-qq&label=%E5%8A%A0%E7%BE%A4)](https://qm.qq.com/q/ueYTU8T3xK)

**这是 Voidkit 的 Bug 维修群。**
装不上、启动崩、模块不生效、配置改了不生效、和别的 mod 冲突 —— 都来群里说。
报 bug 请附上：**版本线**（如 1.21.1 Forge）、**服务端日志**、**相关配置**。

</div>

---

## 支持矩阵与分支对应

| Minecraft | 加载器 | 分支 | 服务端 jar | 客户端 Agent jar |
|---|---|---|---|---|
| 1.21.1 | NeoForge 21.1.x | `master` | `voidkit_guard-0.1.0-beta.1+mc1.21.1-neoforge.jar` | `voidkit_guard_client-0.1.0-beta.1+mc1.21.1-neoforge.jar` |
| 1.21.1 | Forge 52.1.x | `mc/1.21.1-forge` | `voidkit_guard-0.1.0-beta.1+mc1.21.1-forge.jar` | `voidkit_guard_client-0.1.0-beta.1+mc1.21.1-forge.jar` |
| 1.20.1 | Forge 47.4.x | `mc/1.20.1` | `voidkit_guard-0.1.0-beta.1+mc1.20.1-forge.jar` | `voidkit_guard_client-0.1.0-beta.1+mc1.20.1-forge.jar` |

- **本分支 `master` 对应 1.21.1 + NeoForge 21.1.x**（推荐组合）；其它版本线见上表对应分支。
- 所有版本线的 jar 都挂在**同一个 release**（`0.1.0-beta.1`，Pre-release）下，
  按文件名后缀区分，勿混用不同版本线的一对 jar。
- `1.21.1 + Forge` 与 `1.21.1 + NeoForge` 功能完全一致，只是加载器桥不同；
  `1.20.1` 只有 Forge 线。

```
server/build/libs/voidkit_guard-<版本线>-<加载器>.jar         ← 服务端（装服务器，依赖 voidkit_core）
client/build/libs/voidkit_guard_client-<版本线>-<加载器>.jar  ← 客户端（装玩家机器，零依赖）
```

拆成两个 jar 的意义：客户端 jar 物理上不含任何服务端代码 —— 反编译客户端
拿不到服务端的密钥处理、会话管理与检测策略。

---

## 0. 隐私与数据采集

本组件只采集反作弊所需的最少信息：

| 采集项 | 是否采集 | 说明 |
|---|---|---|
| 连接 IP | ✅ | 服务端连接必有的信息，封禁所需 |
| 机器码（加盐哈希） | ✅ | 系统/主板唯一标识哈希，封禁「换号重进」所需；原文不出客户端 |
| 网卡 MAC | ❌ | 不采集（更敏感的个人信息） |
| QQ / 微信痕迹 | ❌ | 不采集 |
| 时区 / 系统区域 / 语言 | ❌ | 不采集（位置类信息） |
| 系统版本 / CPU / GPU / 内存 | ✅ | 设备配置，不指向「谁」只指向「什么机器」，用于虚拟机/软渲染检测 |

- **截图只截 MC 游戏窗口**，不抓玩家屏幕上窗口之外的内容（桌面/任务栏/其它应用）。
- **机器码只上报加盐 SHA-256 哈希**，原文不出客户端；换服盐不同，数据不能跨服串用。
- 服务端代码里**不存在**网卡/QQ/微信的采集路径（不是开关关掉，是物理上没有）。
- 因此封禁维度是 **IP + 机器码 + UUID** 三项；「同源账号挖掘」也只按机器码与 IP 交叉比对。

---

## 1. 架构（为移植而生）

```
voidkit-guard/
├── common/                协议、加密、数据模型 + platform 接口（纯 Kotlin，零 MC、零 core import）
│   ├── common/            协议 Wire/GProtocol、加密 GCrypto/SecretBox、模型 GFingerprint
│   └── platform/          抽象接口 GServer/GChannel/GMods + GuardPlatforms 持有者
├── server/                服务端校验端（modId=voidkit_guard，依赖 voidkit_core）
│   ├── server/            七个 Feature：完整性 / 封禁 / 截屏 / 审计 / 深度扫描 / 挖掘统计 / 战斗遥测
│   ├── GuardBootstrap.kt  加载器无关装配（模块注册 + hub + 生命周期）
│   └── platform/neoforge/ 服务端适配 + @Mod 入口（本分支；换加载器只改这一层）
└── client/                客户端 Agent（modId=voidkit_guard_client，零依赖）
    ├── client/            Agent：握手应答、硬件采集、截屏、深度扫描
    └── platform/neoforge/ 客户端适配 + @Mod 入口
```

- **服务端只依赖 core**，客户端**零依赖**，零跨插件 import（套件铁律）。
- **common 是共享源码目录**（不是独立 Gradle 项目），两个 jar 各编译一份 ——
  所以客户端 jar 里只有 `common/` + `client/` + `platform/`，没有服务端代码。
- **与 core 1.2.0 platform 层对接**（不再自己造轮子）：
  - 游戏目录 → `Platforms.current.gameDir()`（服务端侧）
  - 关服收尾 → `Platforms.current.registerLifecycle(ServerLifecycleListener)`
  - 事件挂载 → core 的 PluginRegistry（`Feature` 生命周期驱动，`onPlayerJoin/onPlayerQuit` 等 VEvent 回调）
- **guard 自己只抽象 core 没有的**：`GMods`（mod 清单枚举 ——
  NeoForge 用 `ModList`，Forge 同样用 `ModList`，Fabric 用 `FabricLoader`，反作弊特有需求）。
- 换加载器 = 各加一个 `platform/<loader>/` 入口文件 + 一个 `<Loader>Mods`，
  `common` / `server` / `client` 业务代码零改动。
  三条版本线已落地：本分支 `platform/neoforge/`（1.21.1 NeoForge）、`mc/1.21.1-forge`
  的 `platform/forge/`（1.21.1 Forge）、1.20.1 Forge 线同理。
  **三个分支的 `common` 与 `server`/`client` 业务代码逐行相同**，
  差异只在 platform 层与构建配置 —— 这是「唯一改写面」铁律的实测结论。

## 2. 七个模块

| 模块 | 命令 | 说明 | 默认 |
|---|---|---|---|
| 客户端完整性 | `/guard` | 智能识别 / 哈希锁双模式。进服握手 + **中途随机复检** + 会话保活 | 开 |
| 封禁 | `/gban` `/gunban` `/gbanlist` `/gcheck` | IP + 机器码 + UUID，任一条命中即拒 | 开 |
| 截屏 | `/gscreen` `/greports` | 按需截屏 + **校验失败静默连拍取证**，分片加密传输 | 开 |
| 审计 | API | 上下线/校验/封禁/截屏/白名单全量记录，SQLite | 开 |
| 深度扫描 | `/gscan` `/greport` | 可控上报本机 IP/文件夹清单/**进程窗口黑名单**/心跳 RTT，输出综合判定报告 | 开 |
| 挖掘统计 | （自动） | 稀有矿占比 + 挖矿暴露度滑动窗口，透视行为**只告警不处置** | 开 |
| 战斗遥测 | （自动） | reach / 瞄准急转 / CPS 机械性，战斗类作弊**只告警不处置** | **关** |

模块开关统一走 core 的 `features` 表：面板「模块」页一键切换，或 core 配置文件
`features.guard_combat = true` —— 每个模块独立启停，互不依赖（审计除外，其它模块的记录都落在它上面）。

## 3. 防护体系（按「能刺到谁」分层）

### 传输与内存（防篡改、防重放）
- 握手：服务端 RSA-2048 → 客户端 AES-256 会话密钥（RSA-OAEP）→ 此后全链路 AES-256-GCM。
- 防重放：auth 回显 nonce；keepalive 每轮换 nonce；截屏/深度扫描按 reqId 绑定。
- 防中间代理篡改：**服务端给每次挑战签名**，客户端用内置公钥环验签 ——
  代理想清空特征库让客户端不扫，改完签名就废。
- **蜜罐协议**（roadmap #3，默认开）：服务端向已验证会话随机（10-30 分钟）
  下发一条「看起来像正常功能、真 agent 协议里不存在」的诱饵消息（`mod_sync`/
  `config_pull`/…）。真 agent 对未知 id 静默丢弃，永不应答；被改过的协议壳
  （剥检测留转发、按 id 全量回执的山寨 agent）一旦回包 = 篡改实锤，
  走失败处置（截图取证 + 踢/封），踢人话术不露蜜罐存在。
- 内存机密保护（对标动视/腾讯思路，JVM 用户态极限）：会话密钥/私钥/指纹只以
  XOR 混淆副本驻留堆（`SecretBox`），明文只在 lambda 里存活几微秒，用完即擦；
  敏感值一律 `char[]/byte[]` 不用 `String`（不可变、进堆就等 GC）。
- Agent 自检：自身 jar 哈希基线 + `-javaagent`/调试器参数检测。

### 服务端（改客户端也没用的部分 —— 主刃）
- **中途复检**：随机间隔（默认 5-15 分钟）换全新 nonce 重走完整握手，
  可选全量重扫 mod 哈希（防「改文件保留 size+mtime」），按概率顺手截图。
  「进服时干净、开局后作弊」在这层现形。
- **哈希锁**：mod 清单（id+version+SHA-256）与白名单逐项比对，多一个少一个都拒。
- **深度扫描**：进程名/窗口标题黑名单（CE、x64dbg、ProcessHacker、各类外挂 overlay）、
  文件夹清单（专抓「mods 目录里没加载的作弊 jar」）、多网卡/VPN 痕迹。
- **综合判定**：多源线索加权评分（确证信号 +100 / 白名单不符 +60 / 可疑进程每个 +50 …），
  输出 clean/suspicious/likely/confirmed 四级结论，每条结论带理由。
  统计检测（挖掘/战斗）告警作为**疑似档**参与评分（单条 +25、合计封顶 55，
  单靠统计到不了「确证」档），理由标注「需人工复核」—— 处置权永远留给服主。
- **归属地（离线）**：内置 ip2region xdb（11MB，打进 jar，首次查询解到数据目录，
  服主可自行替换升级）。**IP 永不出服务器**，不喂任何第三方 API。
  粒度到省市+运营商，用于识别「换宽带/机房 IP」，不是、也不该是定位人的工具。
- **同源账号挖掘**：机器码/IP 交叉撞库，`/gcheck` 与 `/greport` 直接列出
  「这台机器上的所有马甲」。机器码是强关联（换号无效），IP 是弱关联
  （重拨会撞）。关联账号中有人被封 = 换号 evasion，报告 +60 实锤档。
- **联防**：`/guard fence export|import`（或 API `/api/guard/fence`）在自己几个服
  之间同步封禁清单 —— 这服封了，那服自动拦。

### 静默（反侦察）
- 客户端全程零提示零 info 日志（debug 级才输出），无客户端命令。
- 踢人话术只说「环境校验未通过」，**真实原因只进审计 + 在线管理员私信** ——
  对手连自己漏在哪都不知道。
- 截屏无闪光无声响，AWT Robot **只截 MC 游戏窗口区域**（不截全屏，
  不抓玩家屏幕上窗口之外的内容）。

## 4. 威胁模型边界（不夸大）

| 能做到 | 做不到 |
|---|---|
| 赶走「装个透视/杀戮光环就进服」的绝大多数人 | 防不住内核级对手（能读任意进程内存的人）—— 那需要驱动签名，与「几百 mod 整合包零 mixin」冲突，明确不做 |
| 让逆向成本高到不值得（签名挑战 + 随机复检 + 静默） | 防不住「重写一个假 agent」的人 —— 客户端在对方机器上，JVM 层物理上不存在不可破解 |
| 换号不换机的人被机器码封禁抓住 | 换电脑 + 换网络的人（那就真是另一个人了） |
| 执行动作本身会暴露存在（踢人=被发现） | 「完全不被发现」做不到 —— 所以保密的是检测细节，不是存在本身 |

## 5. 性能（客户端零常驻开销）

- **客户端无 tick 循环**：纯载荷驱动，不进事件总线，静态开销 ≈ 0。
- mod 哈希按 (size, mtime) 缓存到 `.minecraft/voidkit_guard/modcache.json`，
  首检几秒、复检毫秒级（`rechallengeFullRescan` 可强制全量）。
- 重活全在后台线程：报告构建、深度扫描（PowerShell 6s 超时强杀）、GPU 读取（1.5s 超时）。
- 服务端：SQLite 单写线程 + WAL 多读 + 有界审计队列（满则丢，绝不堵主线程）；
  `onSecond` 只做内存比较；封禁检查一次 SQL 查完全部身份。

## 6. 部署

### 安装
- 服务端 `mods/` 放 `voidkit_guard-<版本线>-<加载器>.jar` + **同版本线的** `voidkit_core`。
- 玩家客户端只放 `voidkit_guard_client-<版本线>-<加载器>.jar`（**不需要** core）。
- 三条版本线的 jar 都在同一个 release（`0.1.0-beta.1`）里，按文件名后缀挑；
  **core 与 guard 必须同一版本线**，混用会在加载阶段被依赖检查拦下。
- 没装客户端的玩家能进服，但握不上手 → 按 `integrityOnFail` 踢/封。

### 哈希锁开通流程（「先学习，后锁定」）
1. `/guard mode hashlock` —— 白名单为空时自动学习已开启。
2. 玩家进服，清单作为**待审**条目进白名单（`hashlockRequireReview=true` 会拦着等审核）。
3. `/guard whitelist list` 审查 → `/guard whitelist approve-all`（或逐个 approve）。
4. 之后任何新 mod / 被改过的 jar 一律拒之门外。
5. 玩家加了合法新 mod：服主 `/guard whitelist approve <modId>` 后放行。

### 密钥轮换（让旧客户端定期死亡）
1. `/guard key rotate` —— 换服务端密钥对，**旧客户端立刻全部失效**。
2. `/guard key export` 拿新公钥 base64。
3. 贴进客户端 `client/GuardKeys.kt` 的 `RING`，重新构建发布。
4. 被破解的 jar 每轮换一次就要重新破解一次 —— 成本转嫁给对手。

### 数据位置（`<服务端根>/voidkit/guard/`）
```
config.json       带中文注释的全部配置
guard.db          封禁/身份/审计/截屏记录（SQLite WAL）
server.pem        服务端 RSA 私钥（0600，私钥不出服务端）
screenshots/      截屏 PNG（按月分目录）
whitelist.json    哈希锁白名单
```

## 7. 命令与 API

命令：`/guard status|mode|reload|key|whitelist`｜`/gban` `/gunban` `/gbanlist` `/gcheck`｜
`/gscreen` `/greports`｜`/gscan` `/greport`
权限节点（**按破坏性分层**，别一股脑给一个人）：

| 节点 | 覆盖 | 说明 |
|---|---|---|
| `voidkit.guard` | `/guard status\|mode\|reload\|fence export`、`/gscan` `/greport`、`/guard whitelist ...` | 日常运维：看状态、切模式、审白名单 |
| `voidkit.guard.ban` | `/gban` `/gunban` `/gbanlist` `/gcheck` | 封禁与查询 |
| `voidkit.guard.screen` | `/gscreen` `/greports` | 截屏取证 |
| `voidkit.guard.key` | `/guard key rotate` `/guard key export` | 🚨 **不可逆**：rotate 会让**所有已发布客户端立刻失效** |
| `voidkit.guard.fence` | `/guard fence import` | 把别服的封禁清单写进本服 |

- 没装 `voidkit_perm` 时按原版 OP 判定（普通玩家一律拒绝 —— 反作弊没有面向玩家的命令）。
- 这些节点**刻意不并入 `voidkit.admin`**：反作弊权限敏感，应该单独授予，
  而不是「给了管理总权限就顺带拿到封禁/密钥轮换」。

API（经 `voidkit_web` 的 ApiRouter 暴露，给管理面板）：
`GET /api/guard/status`｜`GET /api/guard/sessions`｜`GET/POST /api/guard/bans`｜
`POST /api/guard/bans/lift`｜`GET /api/guard/identities`｜`GET/POST /api/guard/screenshots`｜
`GET /api/guard/screenshots/{id}`（base64）｜`POST /api/guard/scan`｜`GET /api/guard/report/{player}`｜
`GET /api/guard/audit`

## 8. Roadmap（按性价比排序）

1. ✅ **X 射线统计检测**（纯服务端）：每玩家矿石/石头比、挖矿暴露度、滑动窗口。
   证据在服务端日志里，卸客户端也没用。
2. ✅ **战斗遥测**（纯服务端）：reach 超距占比、瞄准急转占比、CPS 机械性（变异系数）。
   与 X 射线同一纪律：只告警不自动处置（`guard/xray-stats.jsonl` / `guard/combat-stats.jsonl` 按需落盘）。
3. ✅ **蜜罐协议**：服务端随机下发真 agent 不认识的诱饵消息，回包即篡改实锤
   （默认开，间隔 10-30 分钟随机，池子可配 `honeypotIds`）。
4. **群体离群评分**：滚动 z-score 对比全服基线（不用 LLM 的「AI 层」）。
5. ~~LLM 读截图~~：不做（需求方明确砍掉）。

## 9. 开发

```bash
./build.sh build       # → server/build/libs/voidkit_guard-<版本线>-<加载器>.jar
                       #   + client/build/libs/voidkit_guard_client-<版本线>-<加载器>.jar
./build.sh runServer   # 开发服务端（core + guard 服务端一起从 classpath 加载）
```

- JDK 21（`C:/Users/26703/.jdks/jdk-21.0.12.1+1`），Kotlin 2.4.21。
- **构建插件按加载器分**：
  | 分支 | 插件 | Gradle |
  |---|---|---|
  | `master`（1.21.1 NeoForge，本分支） | ModDevGradle | 8.12.1 |
  | `mc/1.20.1`（1.20.1 Forge） | ModDevGradle `legacyforge` | 8.12.1 |
  | `mc/1.21.1-forge`（1.21.1 Forge） | **ForgeGradle 7** + `net.minecraftforge.jarjar` | **9.3.1** |

  本分支走 ModDevGradle（NeoForge 21.x）：`mods {}` 块声明模组、
  payload 注册用 `RegisterPayloadHandlersEvent` 的
  `playBidirectional(同一类型, 同一 id)`（**同 id 不能注册两次**），
  由 `networkProtocolVersion` 收敛两端协议版本。
  运行时映射是官方映射 → 同样**不需要 reobf**。

  另两条 Forge 线的差异（移植时踩过的坑，写在这里给后面的人看）：

  ForgeGradle 7 没有 ModDevGradle 的 `mods {}` 块与 `additionalRuntimeClasspath`；
  载荷/channel 注册走老式 `ChannelBuilder` + `SimpleChannel`（**同一载荷类只能注册一次**，
  收发共用一条 2 参 `messageBuilder`，与 NeoForge 的 `playBidirectional` 语义不同）。
  Fat jar 由 **`jarJar` 任务**产出（不是 `jar` 任务），本项目约定 `jar` 退 `-slim`。
  运行时映射是 Mojang 官方映射（不是 SRG）→ **不需要 reobf**，`build/libs` 的 jar 即产物。
- 依赖 **voidkit_core** 对应版本线（仅服务端，platform 适配层）。
  组合构建消费本地 `KokuuWork/kokuucore` 的 `:core`（`settings.gradle` 的 includeBuild）——
  并入主仓库时删掉 includeBuild、依赖改成 `project(':core')` 即可。
- 全 Kotlin，注释讲「为什么」；零 mixin；中文注释。
- 客户端自己 jarJar 一份 `kotlin-stdlib`（服务端由 core 提供）；服务端运行时
  **不要**再补 `kotlin-stdlib`，否则 core 的 jarJar 嵌套 kotlin 与显式依赖撞成两个
  `kotlin.stdlib` 模块，JPMS 直接 `ResolutionException`（服务端起不来）。
