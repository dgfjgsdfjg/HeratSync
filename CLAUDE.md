# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目

HeratSync — AI 陪伴恋人后端。核心差异点：强记忆（Obsidian 式 markdown vault）、拟人化主动消息、Netty 长连接实时对话。单机 MVP，单用户。

## 构建 / 运行 / 测试

Maven 项目（**不是 Gradle**，若见到 `build.gradle`/`gradlew` 是历史残留，应删）。

```bash
mvn clean compile                                   # 编译
mvn test                                             # 全部测试
mvn test -Dtest=com.heartsync.vault.VaultStoreTest   # 单个测试类
DEEPSEEK_API_KEY=sk-xxx mvn spring-boot:run          # 运行（需 API key）
```

- 运行必须提供环境变量 `DEEPSEEK_API_KEY`；`AUTH_TOKEN` 可选（默认 `heartsync-dev-token`）。
- IDEA 运行配置在 `.run/HeratSyncApplication.run.xml`（已含 key + `-Dfile.encoding=UTF-8`），`.run/` 已 gitignore。
- 前端是纯静态 `web/`，改 JS/CSS/HTML 无需重编译，但服务需运行；浏览器可能缓存 `app.js`，用 `?t=xxx` 或硬刷新绕过。
- 端到端验证：`curl localhost:8080/health`，WebSocket 用 `ws://localhost:8081/ws/chat?token=heartsync-dev-token`。

## 架构：双服务器单进程

同一 Spring Boot 进程里跑两个独立服务器：

- **Spring MVC + Tomcat（:8080）** — REST 接口（`controller/`）+ 静态资源（`web/`）。`spring.web.resources.static-locations: file:web/`。
- **手写 Netty WebSocket（:8081）** — `netty/NettyWsServer` 用 `@PostConstruct`/`@PreDestroy` 管生命周期，`sessionManager` 是它 `new` 的实例，通过 `AppConfig` 里 `@Bean` 暴露给 Spring（不是 Spring 扫描出来的）。

Netty pipeline 顺序（`NettyWsServer.start()`）：
`HttpServerCodec → HttpObjectAggregator → AuthHandler → WebSocketServerProtocolHandler → IdleStateHandler → HeartbeatHandler → ChatHandler`

编排核心是 `service/CompanionService`，连接三大能力：`MemoryService`（记忆）、`PersonaService`（人设/状态）、`LlmClient`（DeepSeek 流式）。

## 数据流

**用户消息**：`ChatHandler.channelRead0` → `CompanionService.chat(userId, msg)` → 召回记忆 + 拼人设 prompt → `LlmClient.streamResponse` 返回 `Flux<String>` → 逐 token WebSocket 推回 → `done` 后 `onChatComplete`（更新历史 + 记 `lastInteractionTime` + 异步 `MemoryService.remember`）。

**主动推送**（`PushService`，`@Scheduled` 轮询）：规则闸门（在线/静默期/冷却+抖动/免打扰，配置见 `application.yml` 的 `heartsync.push.*`）→ 过闸才调 `CompanionService.decideProactiveMessage` → LLM 结合最近对话+记忆决策 `PUSH|内容` 或 `SKIP|原因` → 推送或沉默。「该不该推」的智能全在 prompt（`buildProactivePrompt`），不是硬编码规则。

## 记忆系统（vault/）

记忆 = 文件系统的 markdown wiki，**没有数据库**。`vault/` 目录：`persona/`（人设=system prompt 来源）、`people/`、`facts/`（LLM 抽取的事实按实体名建页）、`events/`、`state.md`。

- `VaultStore` 做 md 的 CRUD + frontmatter 解析 + `[[wikilink]]` 抽取（手写 YAML 解析，无 SnakeYAML 依赖）。
- `LuceneIndex` 内存索引（`ByteBuffersDirectory`），启动全量建（`HeratSyncApplication` 的 `CommandLineRunner`），写回后增量更新。字段：path/title/body/type。
- 召回 = BM25 命中 Top-5 + 从命中页 wikilink 一跳扩展 + 按标题去重（`MemoryService.recall`）。
- 写回 = 对话后异步让 LLM 抽取事实（多行，每行 `实体名 | 事实 | 动作`），去重后写入 `facts/{实体}.md`（`MemoryService.remember`/`applyOneFact`）。
- 会话历史、在线状态都是内存 `ConcurrentHashMap`，重启即失；vault 文件持久。

## 关键约束与踩过的坑（改动前必读）

- **DeepSeek 端点**：`base-url` 必须是 OpenAI 兼容的 `https://api.deepseek.com`（客户端是 `OpenAiStreamingChatModel`，会请求 `/chat/completions`）。**不要用 `/anthropic`**——那是 Anthropic 原生格式，会 404 报空 `HttpException`。
- **UTF-8**：中文 Windows + Java 17 默认 GBK，会导致 DeepSeek 流式中文乱码。必须 `-Dfile.encoding=UTF-8`（pom 的 spring-boot 插件和 `.run` 配置都已设）。
- **WebSocket 握手路径**：`WebSocketServerProtocolConfig` 必须 `checkStartsWith(true)`，否则带 `?token=` 的 URI 精确匹配失败，不握手、客户端卡「连接中」。
- **ByteBuf 释放**：`ChatHandler` 是 `SimpleChannelInboundHandler`，父类**自动释放** frame，不要再手动 `release`（双重释放抛 `IllegalReferenceCountException` → 断连）。
- **连接管理**：所有人共用 userId `user-heartsyn`（单用户阶段）。`WsSessionManager.onConnect` **不能踢旧连接**（会导致多标签页互踢重连风暴）；`onDisconnect` 用条件移除 `remove(userId, channel)`。
- **心跳**：靠服务端主动发 WS PING 控制帧（`HeartbeatHandler` 在 `ALL_IDLE` 触发），浏览器协议层自动 PONG——**不依赖前端 JS 定时器**（后台标签页会冻结 JS）。需 `dropPongFrames(false)` 让 PONG 下传重置 `READER_IDLE`（120s 硬关，40s 触发 ping）。

## 演进方向（阶段 2，未实现）

多用户隔离（`vault/{userId}/`、每人 token/JWT、索引按 userId 隔离）、向量重排（Ollama nomic-embed）、Redis 集群（在线状态 + Pub/Sub 跨节点推送 + Nginx 粘性会话）。设计文档见 `docs/superpowers/specs/` 与 `docs/superpowers/plans/`。
