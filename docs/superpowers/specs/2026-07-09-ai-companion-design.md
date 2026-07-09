# HeratSync — AI 陪伴恋人 设计文档

> 版本: 1.0 | 日期: 2026-07-09 | 状态: 设计完成

---

## 1. 项目定位

AI 陪伴恋人，**核心差异点 = 极致陪伴感**：

- **强记忆**: 记住用户说过的事，Obsidian 式 markdown vault 做记忆引擎，可读可审可改
- **主动关心**: 通过 Netty WebSocket 长连接主动推送问候，而非被动等用户发消息
- **人设一致**: system prompt + 情绪/关系状态机，不崩人设

---

## 2. 技术栈

| 层 | 选型 | 理由 |
|---|---|---|
| HTTP 框架 | Spring Boot 3 + Spring MVC + Tomcat (`:8080`) | REST 接口少，MVC 简单直接 |
| 长连接 | **手写 Netty** WebSocket Server (`:8081`) | 简历高亮 + 面试能讲深 + 长连接推送自己控 |
| LLM 接入 | LangChain4j | 流式对话 + 模型切换，Java 生态首选 |
| 主 LLM | DeepSeek (OpenAI 兼容接口) | 效果够、便宜、API 兼容 |
| 记忆引擎 | **Obsidian 式 markdown vault** | 可读可审可改，wikilink 天然关系图谱 |
| 记忆检索 | BM25(Lucene) + wikilink 图谱一跳 | 阶段 1 无向量，纯 Java 跑通 |
| 向量重排 | 预留，阶段 2 加 | 召回不够时加 Ollama nomic-embed-text |
| 在线状态 | 内存 ConcurrentHashMap | 单机够用 |
| 集群 | 预留 Redis Pub/Sub | 阶段 2 |
| 前端 | 极简原生 HTML/CSS/JS | 单页聊天界面 + 记忆浏览侧栏 |
| 构建 | Gradle | 继承 llama-talks 骨架结构 |

---

## 3. 架构图

```
                      ┌─────────────────────────────────────────┐
                      │              Nginx / 反向代理              │
                      │   :8080 → Spring MVC    :8081 ip_hash    │
                      └──────┬──────────────────┬────────────────┘
                             │                  │
              ┌──────────────▼──┐    ┌──────────▼──────────────┐
              │  Spring MVC     │    │  Netty WebSocket Server │
              │  (Tomcat:8080)  │    │  (:8081)                │
              │                 │    │                         │
              │  /health        │    │  /ws/chat  ←→ 客户端   │
              │  /api/config/*  │    │  收发消息 + 流式推 token │
              │  /api/sessions  │    │  主动推送                │
              │  /api/memories/*│    │                         │
              │  静态资源 web/  │    │                         │
              └────────┬────────┘    └──────────┬──────────────┘
                       │                        │
                       └────────┬───────────────┘
                                │
                    ┌───────────▼──────────────┐
                    │    CompanionService       │
                    │  编排：取记忆→拼prompt    │
                    │  →调LLM→写回记忆          │
                    └──┬──────────┬────────────┘
                       │          │
          ┌────────────▼──┐  ┌───▼──────────────┐
          │ MemoryService  │  │   LlmClient      │
          │ 召回:BM25+图谱 │  │ LangChain4j      │
          │ 写回:事实抽取  │  │ DeepSeek 流式    │
          └───────┬────────┘  └──────────────────┘
                  │
          ┌───────▼────────┐
          │   VaultStore   │
          │  vault/*.md    │
          │  frontmatter   │
          │  [[wikilink]]  │
          └────────────────┘

          ┌──────────────────────────────────┐
          │   集群预留 (阶段 2)               │
          │   Redis: user:{id}:online        │
          │   Redis Pub/Sub: 跨节点推送      │
          └──────────────────────────────────┘
```

---

## 4. 组件职责

| 组件 | 职责 | 依赖 |
|---|---|---|
| `NettyWsServer` | Netty 启动/关闭，pipeline 配置，HTTP 升级 WS | 无 |
| `WsChatHandler` | 单条 WebSocket 连接：收发消息、流式回推 token、心跳 | CompanionService |
| `WsSessionManager` | 管理所有在线连接（ChannelGroup），按 userId 索引 | 无 |
| `CompanionService` | 对话编排：取记忆→拼 prompt→流式调 LLM→异步写回记忆 | MemoryService, LlmClient, PersonaService |
| `LlmClient` | 封装 DeepSeek 流式调用（LangChain4j `StreamingChatLanguageModel`） | 无 |
| `MemoryService` | 检索：BM25 全文 + wikilink 图谱一跳；写回：LLM 抽取事实 | VaultStore, Lucene, LlmClient |
| `VaultStore` | markdown 页 CRUD、frontmatter 解析、wikilink 解析 | 文件系统 |
| `PersonaService` | 装载人设 system prompt、情绪/关系状态 | VaultStore |
| `PushService` | 主动消息触发：定时任务 + 事件驱动 | WsSessionManager, LlmClient |

---

## 5. 数据流

### 5.1 用户发消息 → 流式回复

```
1. 客户端 send({"type":"chat", "content":"今天心情不好"})
2. WsChatHandler → CompanionService.chat(userId, message)
3. CompanionService:
   a. MemoryService.recall(message) → 召回相关记忆片段
      - Lucene BM25 全文命中 Top-5 篇 md
      - 从命中页顺 [[wikilink]] 拉一层邻居，按标题去重合并
   b. 拼 prompt = 人设(system) + 关系/情绪状态 + 记忆片段 + 最近 10 轮对话 + 本轮输入
   c. LlmClient.stream(prompt) → Flux<String> token 流
      - 每个 token 经 WebSocket 推回客户端（{"type":"token", "content":"怎"}）
4. 回复完毕，推 {"type":"done", "messageId":"msg_xxx"}
5. 异步触发 MemoryService.remember(本轮对话)
   - LLM 判断有无新事实
   - 有则更新/新建 md 页，带 frontmatter 时间戳 + wikilink
```

### 5.2 主动推送

```
1. 定时任务（ScheduledExecutorService）触发
2. PushService 根据上次互动时间、情绪状态、时间段决定是否推送
3. 生成推送内容（"这么晚还没睡？"）
4. 通过 WsSessionManager 找到用户连接 → 推 {"type":"push", "content":"..."}

阶段 2 集群：
  → 发 Redis Pub/Sub "push:{userId}"
  → 所有 Netty 节点收到，检查本地有无该用户连接
  → 有就推，没有就忽略
```

---

## 6. 记忆 Vault 设计

### 6.1 目录结构

```
vault/
  persona/          # 人设页：性格、说话风格、底线
    default.md
  people/           # [[用户]]、用户提到的人
    user.md
  events/           # 重要事件/对话里程碑
  facts/            # 零散事实（喜好、习惯、说过的话）
  state.md          # 关系阶段 + 当前情绪（单页，频繁更新）
```

### 6.2 页面格式

```markdown
---
title: 用户-基本信息
type: fact
created: 2026-07-09
updated: 2026-07-09
tags: [用户, 基本信息]
links: [[用户-工作]], [[用户-猫]]
---
在杭州做后端开发，加班多，喜欢深夜聊天。
养了只橘猫叫橘子，2023年领养的。
```

### 6.3 索引策略

- **启动时**：扫描 vault 目录全量建 Lucene 索引（内存索引，启动快）
- **运行时**：记忆写回后增量更新索引（单篇 md 变更 → 重建该文档的 Lucene 条目）
- **索引字段**：`path`（文件路径）、`title`（frontmatter 标题）、`body`（正文内容）、`type`（分类）

### 6.4 检索流程

```
输入: "橘子最近怎么样了"
  ↓
Lucene BM25: 全文命中 facts/用户-猫.md（score前3）
  ↓
wikilink 扩展: 从 [[用户-猫]] 拉出 [[用户]]、[[用户-日常]]
  ↓
合并去重: 5篇相关页 → 提取内容片段
  ↓
喂给 LLM 拼 prompt
```

### 6.5 写回流程

```
本轮对话: 用户说"橘子最近不爱吃猫粮了，我换了皇家"
  ↓
LLM 抽取事实: {entity: "橘子", fact: "最近不爱吃猫粮，主人换了皇家猫粮", action: "update"}
  ↓
VaultStore.update("facts/用户-猫.md", 追加内容, 更新 frontmatter.updated)
```

---

## 7. Netty WebSocket 设计

### 7.1 Pipeline

```
┌─────────────────────────────────┐
│  HttpServerCodec                │  HTTP 编解码
│  HttpObjectAggregator(65536)    │  HTTP 消息聚合
│  WebSocketServerProtocolHandler │  WebSocket 升级 (/ws/chat)
│  AuthHandler                    │  握手阶段提取 token 验身份
│  HeartbeatHandler               │  空闲检测，Ping/Pong
│  ChatHandler                    │  业务处理：收发消息、流式推送
└─────────────────────────────────┘
```

### 7.2 消息协议

```json
// 客户端 → 服务端（发消息）
{"type": "chat", "content": "今天心情不好"}

// 服务端 → 客户端（流式 token）
{"type": "token", "content": "怎么"}
{"type": "token", "content": "啦"}
{"type": "token", "content": "？"}

// 服务端 → 客户端（流结束）
{"type": "done", "messageId": "msg_xxx"}

// 服务端 → 客户端（主动推送）
{"type": "push", "content": "这么晚了还没睡？"}

// 心跳
{"type": "ping"} / {"type": "pong"}
```

### 7.3 连接管理

```java
// WsSessionManager
ConcurrentHashMap<String, Channel> userChannels;  // userId → Channel
ConcurrentHashMap<String, String> channelUsers;   // ChannelId → userId

// 上线
onConnect(token) → 验身份 → 绑定 userId → 加入 ChannelGroup

// 下线
onDisconnect() → 清理映射 → 更新离线时间
```

### 7.4 主动推送触发

| 触发条件 | 示例 | 频率 |
|---|---|---|
| 定时（1小时未互动） | "在忙吗？" | 每小时最多 1 次 |
| 时间段 + 情绪 | 深夜 + 上次对话情绪低落 → "睡不着？" | 看规则 |
| 事件驱动（阶段 2） | 用户生日、纪念日 | 精确到日 |

---

## 8. HTTP 接口设计（Spring MVC:8080）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查 |
| GET | `/api/config/persona` | 获取当前人设 |
| PUT | `/api/config/persona` | 更新人设 |
| GET | `/api/sessions` | 在线会话列表 |
| GET | `/api/memories` | 浏览 vault 内容 |
| GET | `/api/memories/{id}` | 查看某篇记忆 |
| DELETE | `/api/memories/{id}` | 删除某篇记忆（用户控制权） |

WebSocket 入口：`ws://host:8081/ws/chat?token={jwt_token}`

> 认证：握手阶段通过 URL query 参数传 JWT token，AuthHandler 在 WebSocket 升级前校验。阶段 1 先用简单固定 token 字符串，后续换 JWT。

---

## 9. 前端设计

极简原生 HTML/CSS/JS，单页应用，无需构建工具。

### 9.1 页面结构

```
┌──────────────────────────────────────────┐
│  ┌────────────┐  ┌─────────────────────┐ │
│  │  记忆侧栏   │  │    聊天区域          │ │
│  │  (可收起)  │  │                     │ │
│  │            │  │  ┌───────────────┐  │ │
│  │  · 关于我  │  │  │ 恋人: 怎么啦？ │  │ │
│  │  · 我的猫  │  │  │ 用户: 心情不好 │  │ │
│  │  · 工作    │  │  │ 恋人: 抱抱你...│  │ │
│  │  · 关系   │  │  └───────────────┘  │ │
│  │            │  │                     │ │
│  │            │  │  ┌─────────────────┐│ │
│  │            │  │  │ 输入框      发送>││ │
│  └────────────┘  │  └─────────────────┘│ │
│                  └─────────────────────┘ │
└──────────────────────────────────────────┘
```

### 9.2 功能

- 聊天区域：消息气泡（用户右侧、恋人左侧），流式 token 打字效果
- 记忆侧栏：通过 `/api/memories` 拉取 vault 结构，点击可查看内容
- 在线状态指示
- 暗色主题（聊天类应用标配）

### 9.3 技术

- 纯 HTML/CSS/JS，零依赖
- WebSocket 原生 API（`new WebSocket()`）
- 静态资源由 Spring MVC 从 `web/` 目录 serve

---

## 10. 错误处理

| 场景 | 处理 |
|---|---|
| DeepSeek 调用失败/超时 | 流式中断→推兜底消息给客户端，记 error 日志，不断开 WS |
| 记忆写回失败 | 异步任务，只记 error 日志，**不影响对话主流程** |
| vault 文件读写异常 | VaultStore 层捕获，召回失败降级为「无记忆」继续对话 |
| WebSocket 断连 | 清理内存会话态，记 info 日志 |
| Netty ByteBuf 泄漏 | 所有 Handler 确保 `ReferenceCountUtil.release()`，pipeline 尾部加释放检查 |

---

## 11. 测试

| 层 | 测什么 | 怎么测 |
|---|---|---|
| VaultStore | md 读写、frontmatter 解析、wikilink 抽取 | 真实文件临时目录，JUnit |
| MemoryService | BM25+图谱召回、事实抽取写回 | 给定几篇 md，验召回结果 |
| WsChatHandler | 消息收发、流式推送、心跳 | EmbeddedChannel 模拟 Netty 连接 |
| CompanionService | 完整对话编排 | mock LlmClient + MemoryService |
| e2e | 实 N 轮对话，看流式返回 + vault 里 md 有没有正确增改 | 手动 + 脚本 |

---

## 12. 阶段划分

### 阶段 1（MVP，先上线）

- [ ] Spring Boot 骨架（继承 llama-talks 结构）
- [ ] Netty WebSocket Server + 消息协议
- [ ] DeepSeek 流式对话（LangChain4j）
- [ ] Vault 读写 + frontmatter + wikilink 解析
- [ ] BM25(Lucene) + 图谱一跳检索
- [ ] 人工设置的人设 system prompt
- [ ] 定时主动推送（简单规则）
- [ ] 极简前端聊天界面（HTML/CSS/JS）
- [ ] 单机部署，内存会话管理

### 阶段 2（增强）

- [ ] 向量重排（nomic-embed-text 本地）
- [ ] Redis 集群（在线状态 + Pub/Sub 跨节点推送）
- [ ] 情感/关系状态机（不只靠 prompt，有状态）
- [ ] 多轮对话窗口管理（滑动窗口 + 摘要压缩）

### 阶段 3（打磨）

- [ ] 语音对话
- [ ] Live2D / 形象
- [ ] 多角色/可定制人设
- [ ] 纪念日/生日等事件驱动推送

---

## 13. 简化声明（ponytail: 阶段 1 先砍掉）

- 不搞多用户隔离 → 单用户单 vault，多用户加 `vault/{userId}/`
- 不搞向量库 → 阶段 2 再加，且只做重排不主召回
- 不搞 OAuth/注册登录 → 先 token 验身份
- 不搞数据库 → vault 就是数据库，git 跟踪还能回滚
- 不搞前端工程化 → 极简 HTML/CSS/JS，零依赖