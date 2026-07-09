# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

HeratSync 是一个 AI 陪伴恋人应用。用户通过 Web 前端建立 WebSocket 连接，与服务端进行流式对话。服务端调用 DeepSeek LLM，结合本地 Obsidian 式 markdown vault 中的"记忆"做 RAG 检索增强，提供个性化恋人角色扮演体验。

## 核心构建命令

```bash
# 编译 & 运行测试
mvn clean test

# 单独运行某个测试类
mvn test -Dtest=CompanionServiceTest

# 启动应用（JVM 强制 UTF-8，避免 DeepSeek 流式中文乱码）
mvn spring-boot:run

# 打包可执行 jar
mvn clean package -DskipTests
```

- Java 17，Spring Boot 3.4.1
- 启动前需设环境变量 `DEEPSEEK_API_KEY=sk-xxx`，否则 LLM 调用会失败
- 项目路径不建议有中文名（`ForkJoinPool.commonPool` 会解析路径出错）

## 架构概览

### 双端口架构

| 端口 | 协议 | 用途 |
|------|------|------|
| 8080 | HTTP | Spring MVC REST API（配置、记忆管理、健康检查、前端静态资源） |
| 8081 | WebSocket | Netty 手写 WebSocket 服务（聊天流式通信） |

Tomcat（8080）和 Netty（8081）是两个独立的服务，不在同一个端口上运行。

### 核心数据流（一次对话请求）

```
用户浏览器 --[WebSocket/8081]--> NettyWsServer
  -> AuthHandler（握手阶段 token 认证）
    -> HeartbeatHandler（60s 无读断开）
      -> ChatHandler
        -> CompanionService.chat(userId, message)
          1. MemoryService.recall(message)    ← Lucene BM25 检索 vault
          2. PersonaService.loadSystemPrompt() ← 读取 vault/persona/default.md
          3. LlmClient.streamResponse()       ← 流式调用 DeepSeek
          4. 每个 token 实时写回 WebSocket
          5. onChatComplete() → 更新对话历史 + 异步记忆写回
```

### 关键组件 & 职责

| 组件 | 路径 | 职责 |
|------|------|------|
| `CompanionService` | `service/` | **对话编排核心**：串联 Memory → Persona → LLM → 异步写回 |
| `LlmClient` | `service/` | DeepSeek 流式调用封装（基于 LangChain4j `OpenAiStreamingChatModel`），输出 `Flux<String>` |
| `MemoryService` | `service/` | BM25 召回 + [[wikilink]] 一跳图谱扩展 + LLM 事实抽取写回；`recall()` 返回拼装好的记忆文本 |
| `PersonaService` | `service/` | 读取 `vault/persona/default.md` 作为 system prompt，管理关系状态（`state.md`） |
| `PushService` | `service/` | `@Scheduled` 每 30 分钟检查，按时间段推送问候消息（早安/晚安） |
| `VaultStore` | `vault/` | Obsidian 式 markdown 文件系统 CRUD：frontmatter 解析、[[wikilink]] 提取、YAML 序列化 |
| `LuceneIndex` | `vault/` | 内存 BM25 全文检索索引（`ByteBuffersDirectory`），启动时 `CommandLineRunner` 全量构建 |
| `NettyWsServer` | `netty/` | Netty WebSocket 服务端，`@PostConstruct` 启动，`@PreDestroy` 关闭 |
| `WsSessionManager` | `netty/` | userId ↔ Channel 双向映射，支持在线用户查询和主动推送 |
| `ChatMessage` | `model/` | WebSocket 消息协议：`chat` / `token` / `done` / `push` / `ping` / `pong` |
| `VaultPage` | `model/` | Markdown 页内存表示：`path` / `title` / `type` / `tags` / `links` / `content` |

### Vault 文件结构

```
vault/
├── persona/default.md  ← 人设 system prompt，可热更新
├── state.md            ← 关系状态：关系阶段、当前情绪、上次互动
├── people/user.md      ← 用户档案（对话中逐渐填充）
├── facts/              ← LLM 抽取的事实（对话自动创建）
├── events/             ← 事件页
```

### Netty 管道链（ChannelPipeline）

```
HttpServerCodec → HttpObjectAggregator(64KB) → AuthHandler
  → WebSocketServerProtocolHandler → IdleStateHandler(60s)
    → HeartbeatHandler → ChatHandler
```

- `AuthHandler` 认证成功后从 pipeline 中移除自己
- `checkStartsWith=true` 允许 WebSocket URI 携带 query 参数
- `SimpleChannelInboundHandler` 自动释放 `TextWebSocketFrame`，不要手动 release

### 记忆扩展逻辑（MemoryService）

BM25 检索 Top-5 后，对每篇文档的正文提取 `[[wikilink]]`，做一跳图谱扩展——通过 `VaultStore.findByTitle()` 查找被链接的页面并加入结果集。按标题去重。

### 技术栈

- Spring Boot 3.4.1 + Java 17
- Netty 4.1.115（手写 WebSocket，非 Spring WebSocket）
- LangChain4j 1.0.0-beta2（OpenAI 兼容接口调用 DeepSeek）
- Lucene 9.12.0（BM25 全文检索）
- Reactor（`Flux` + `Sinks` 流式桥接）

## 测试注释约定

测试中大量使用 `// ponytail: ...` 注释标记临时/简化实现，表示这是技术债务标记，阶段 2 应被替换为正式实现。代码中也常见此标记。

## 注意

- 不支持多用户并发连接（阶段 1 目标单一用户），`conversationHistory` 是内存 `ConcurrentHashMap`
- `MemoryService.extractFactSync()` 目前返回 `NONE` 占位，事实抽取尚未接通 LLM
- 测试不依赖 Spring 容器，使用手写匿名类 mock（不引入 Mockito）
