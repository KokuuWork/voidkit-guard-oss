# VoidkitGuard —— 反作弊组件

NeoForge **1.21.1**，Voidkit 套件（`KokuuWork/voidkit`）的第 9 个组件：**客户端 Agent + 服务端校验**。
客户端与服务端是同一个 jar（`side = BOTH`），只依赖 `voidkit_core`。

```
guard/build/libs/voidkit_guard-1.0.0.jar   ← 唯一产物，客户端服务端都装它
```

---

## 1. 架构（为移植而生）

```
guard/
├── common/        协议、加密、内存机密保护（纯 Kotlin，零 MC import）
├── platform/      抽象接口 GServer/GChannel/GMods + GuardPlatforms 持有者
│   └── neoforge/  ★ 唯一碰 net.neoforged.* 的一层：适配实现 + @Mod 入口
├── server/        五个 Feature：完整性 / 封禁 / 截屏 / 审计 / 深度扫描
├── client/        Agent：握手应答、硬件采集、截屏、深度扫描
└── GuardBootstrap.kt   加载器无关装配（模块注册 + hub + 生命周期）
```

- **插件只依赖 core**，零跨插件 import（套件铁律）。
- **与 core 1.2.0 platform 层对接**（不再自己造轮子）：
  - 游戏目录 → `Platforms.current.gameDir()`
  - 关服收尾 → `Platforms.current.registerLifecycle(ServerLifecycleListener)`
  - 事件挂载 → core 的 PluginRegistry（`Feature` 生命周期驱动）
- **guard 自己只抽象 core 没有的**：`GMods`（mod 清单枚举 ——
  NeoForge 用 `ModList`，Fabric 用 `FabricLoader`，反作弊特有需求）。
- 换加载器 = 新增 `platform/fabric/`（一个入口文件 + 一个 `FabricMods`），
  `common` / `server` / `client` 业务代码零改动。
- 剩余迁移面（等 core 的 VEvent 桥接落地后一起搬）：`server/` 下
  BanFeature / IntegrityFeature 里各一处 `@SubscribeEvent` 登录/登出监听
  （约 20 行，改成覆写 `Feature.onPlayerJoin/onPlayerQuit` 即可）。

## 2. 五个模块

| 模块 | 命令 | 说明 |
|---|---|---|
| 客户端完整性 | `/guard` | 智能识别 / 哈希锁双模式。进服握手 + **中途随机复检** + 会话保活 |
| 封禁 | `/gban` `/gunban` `/gbanlist` `/gcheck` | IP + 主板机器码 + 网卡 MAC + QQ 登录记录，任一条命中即拒 |
| 截屏 | `/gscreen` `/greports` | 按需截屏 + **校验失败静默连拍取证**，分片加密传输 |
| 审计 | API | 上下线/校验/封禁/截屏/白名单全量记录，SQLite |
| 深度扫描 | `/gscan` `/greport` | 可控上报本机 IP/文件夹清单/**进程窗口黑名单**/心跳 RTT，输出综合判定报告 |

## 3. 防护体系（按「能刺到谁」分层）

### 传输与内存（防篡改、防重放）
- 握手：服务端 RSA-2048 → 客户端 AES-256 会话密钥（RSA-OAEP）→ 此后全链路 AES-256-GCM。
- 防重放：auth 回显 nonce；keepalive 每轮换 nonce；截屏/深度扫描按 reqId 绑定。
- 防中间代理篡改：**服务端给每次挑战签名**，客户端用内置公钥环验签 ——
  代理想清空特征库让客户端不扫，改完签名就废。
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

### 静默（反侦察）
- 客户端全程零提示零 info 日志（debug 级才输出），无客户端命令。
- 踢人话术只说「环境校验未通过」，**真实原因只进审计 + 在线管理员私信** ——
  对手连自己漏在哪都不知道。
- 截屏无闪光无声响，AWT Robot 抓屏幕区域（覆盖在游戏窗口上的 overlay 一样进图）。

## 4. 威胁模型边界（不夸大）

| 能做到 | 做不到 |
|---|---|
| 赶走「装个透视/杀戮光环就进服」的绝大多数人 | 防不住内核级对手（能读任意进程内存的人）—— 那需要驱动签名，与「几百 mod 整合包零 mixin」冲突，明确不做 |
| 让逆向成本高到不值得（签名挑战 + 随机复检 + 静默） | 防不住「重写一个假 agent」的人 —— 客户端在对方机器上，JVM 层物理上不存在不可破解 |
| 换号不换机的人被机器码/MAC/QQ 封禁抓住 | 换电脑 + 换 QQ + 换网络的人（那就真是另一个人了） |
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
服务端 `mods/` 放 `voidkit_guard-1.0.0.jar` + `voidkit_core`；玩家客户端同样放这两个 jar。
没装客户端的玩家能进服，但握不上手 → 按 `integrityOnFail` 踢/封。

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
权限节点：`voidkit.guard`（总）／`voidkit.guard.ban`／`voidkit.guard.screen`。

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
3. **蜜罐协议**：发假消息 id，真 agent 不理、被改的客户端乱回。
4. **群体离群评分**：滚动 z-score 对比全服基线（不用 LLM 的「AI 层」）。
5. **LLM 读截图/报告**（可选层，契合 Voidkit AI 卖点）：截屏已落盘，接模型做语义分析。

## 9. 开发

```bash
./build.sh build       # → guard/build/libs/voidkit_guard-1.0.0.jar
./build.sh runServer   # 开发服务端（core + guard 一起从 classpath 加载）
```

- JDK 21（`C:/Users/26703/.jdks/jdk-21.0.12.1+1`），Gradle 8.12.1，Kotlin 2.1.0。
- 依赖 **voidkit_core 1.2.0**（platform 适配层版本）。组合构建消费本地
  `KokuuWork/voidkit` 仓库的 `:core`（`settings.gradle` 的 includeBuild）——
  并入主仓库时删掉 includeBuild、依赖改成 `project(':core')` 即可。
- 全 Kotlin，注释讲「为什么」；零 mixin；中文注释。
- ⚠️ 组合构建下 guard 运行时**不要**自己补 `additionalRuntimeClasspath kotlin-stdlib` ——
  core 的 jarJar 嵌套 kotlin 会上运行时 classpath，两边都是 module-named jar，
  JPMS 直接 `ResolutionException`（服务端起不来）。kotlin 运行时由 core 提供。
