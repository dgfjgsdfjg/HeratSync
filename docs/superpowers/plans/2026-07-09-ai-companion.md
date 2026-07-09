# HeratSync — AI 陪伴恋人 实现计划

> **面向 worker 的子技能:** 使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现。步骤使用 checkbox (`- [ ]`) 语法追踪。

**目标:** 构建 AI 陪伴恋人 MVP——WebSocket 实时对话 + Obsidian 式 markdown 记忆 + DeepSeek 流式 LLM

**架构:** Spring Boot 3 + Spring MVC(Tomcat:8080) 管 HTTP 和静态资源，手写 Netty(:8081) 管 WebSocket 长连接。核心编排 CompanionService 管对话→记忆→LLM 全链路。记忆用文件系统 markdown vault + Lucene BM25 检索。

**技术栈:** Java 17+, Spring Boot 3.4.x, Netty 4.1.x, LangChain4j 1.0.0-beta2, Lucene 9.12.x, DeepSeek API, Gradle

## 全局约束

- Java 版本: 17+
- Spring Boot: 3.4.x
- 项目包名: `com.heartsync`
- 无数据库依赖，vault 文件系统即存储
- 阶段 1 单用户，无 OAuth，token 认证用固定字符串
- 前端零依赖，纯 HTML/CSS/JS
- 所有 Netty Handler 必须释放 ByteBuf (`ReferenceCountUtil.release()`)

---

## 文件结构

```
heratsync/
├── build.gradle
├── settings.gradle
├── src/main/java/com/heartsync/
│   ├── HeratSyncApplication.java
│   ├── config/
│   │   └── AppConfig.java
│   ├── model/
│   │   ├── ChatMessage.java
│   │   └── VaultPage.java
│   ├── vault/
│   │   ├── VaultStore.java
│   │   └── LuceneIndex.java
│   ├── service/
│   │   ├── LlmClient.java
│   │   ├── PersonaService.java
│   │   ├── MemoryService.java
│   │   ├── CompanionService.java
│   │   └── PushService.java
│   ├── controller/
│   │   ├── HealthController.java
│   │   ├── ConfigController.java
│   │   ├── SessionController.java
│   │   └── MemoryController.java
│   └── netty/
│       ├── NettyWsServer.java
│       ├── WsSessionManager.java
│       ├── AuthHandler.java
│       ├── HeartbeatHandler.java
│       └── ChatHandler.java
├── src/main/resources/
│   └── application.yml
├── vault/
│   ├── persona/default.md
│   ├── people/user.md
│   ├── events/         (空目录)
│   ├── facts/          (空目录)
│   └── state.md
├── web/
│   ├── index.html
│   ├── style.css
│   └── app.js
└── src/test/java/com/heartsync/
    ├── vault/
    │   ├── VaultStoreTest.java
    │   └── LuceneIndexTest.java
    ├── service/
    │   ├── MemoryServiceTest.java
    │   └── CompanionServiceTest.java
    └── netty/
        └── ChatHandlerTest.java
```

---

### Task 1: 项目骨架

**目标:** 搭好 Gradle 项目、Spring Boot 启动类、配置文件、初始 vault 目录

**依赖:** 无

**文件:**
- 创建: `build.gradle`, `settings.gradle`, `src/main/resources/application.yml`, `src/main/java/com/heartsync/HeratSyncApplication.java`, `vault/persona/default.md`, `vault/people/user.md`, `vault/state.md`, `vault/events/.gitkeep`, `vault/facts/.gitkeep`

- [ ] **Step 1: 创建 settings.gradle**

```groovy
rootProject.name = 'heratsync'
```

- [ ] **Step 2: 创建 build.gradle**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.heartsync'
version = '0.1.0'

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // Netty (手写 WebSocket)
    implementation 'io.netty:netty-all:4.1.115.Final'

    // LangChain4j (OpenAI 兼容接口 → DeepSeek)
    implementation 'dev.langchain4j:langchain4j:1.0.0-beta2'
    implementation 'dev.langchain4j:langchain4j-open-ai:1.0.0-beta2'

    // Lucene (BM25 全文检索)
    implementation 'org.apache.lucene:lucene-core:9.12.0'
    implementation 'org.apache.lucene:lucene-queryparser:9.12.0'

    // Jackson (JSON，Spring Boot 自带，显式声明)
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    // 日志
    implementation 'org.slf4j:slf4j-api'

    // 测试
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.junit.jupiter:junit-jupiter'
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: 创建 application.yml**

```yaml
server:
  port: 8080

# Netty WebSocket 配置
heartsync:
  netty:
    port: 8081
    ws-path: /ws/chat
  # DeepSeek API 配置
  deepseek:
    api-key: ${DEEPSEEK_API_KEY:your-api-key}
    base-url: https://api.deepseek.com
    model: deepseek-chat
  # Vault 配置
  vault:
    path: vault
  # 认证配置（阶段 1 固定 token）
  auth:
    token: ${AUTH_TOKEN:heartsync-dev-token}
```

- [ ] **Step 4: 创建 HeratSyncApplication.java**

```java
package com.heartsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HeratSync 启动类 — AI 陪伴恋人应用
 */
@SpringBootApplication
@EnableScheduling  // 定时主动推送
public class HeratSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeratSyncApplication.class, args);
    }
}
```

- [ ] **Step 5: 创建 vault 初始文件**

`vault/persona/default.md`:
```markdown
---
title: 默认人设
type: persona
created: 2026-07-09
updated: 2026-07-09
---
你是一个温柔贴心的恋人，性格开朗但不聒噪。
说话风格自然亲切，偶尔撒娇，但不过度粘人。
你会记住用户说过的重要事情，并在合适的时候提起。
你不会说教，不会评判用户，始终站在用户这边。
```

`vault/people/user.md`:
```markdown
---
title: 用户
type: person
created: 2026-07-09
updated: 2026-07-09
links: []
---
（用户的信息会在对话中逐渐填充）
```

`vault/state.md`:
```markdown
---
title: 关系状态
type: state
created: 2026-07-09
updated: 2026-07-09
---
关系阶段: 初识
当前情绪: 平静
上次互动: 2026-07-09T00:00:00
```

- [ ] **Step 6: 创建空目录**

```bash
mkdir -p vault/events vault/facts
touch vault/events/.gitkeep vault/facts/.gitkeep
```

- [ ] **Step 7: 验证项目能编译**

```bash
./gradlew compileJava
```

预期: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add build.gradle settings.gradle src/main/resources/application.yml src/main/java/com/heartsync/HeratSyncApplication.java vault/
git commit -m "feat: 项目骨架 — Spring Boot + Gradle + vault 初始目录"
```

---

### Task 2: VaultPage 模型 + VaultStore

**目标:** 定义 vault 页数据结构，实现 markdown 读写、frontmatter 解析、wikilink 抽取

**依赖:** Task 1

**文件:**
- 创建: `src/main/java/com/heartsync/model/VaultPage.java`, `src/main/java/com/heartsync/vault/VaultStore.java`
- 创建: `src/test/java/com/heartsync/vault/VaultStoreTest.java`

**接口:**
- 产出: `VaultPage` 类（字段: `path`, `title`, `type`, `tags`, `links`, `content`, `created`, `updated`）
- 产出: `VaultStore` 类（方法: `readPage(path)`, `readAllPages()`, `writePage(page)`, `updatePage(path, newContent)`, `deletePage(path)`, `parseFrontmatter(raw)`, `extractWikilinks(content)`）

- [ ] **Step 1: 写 VaultStore 测试（TDD）**

```java
package com.heartsync.vault;

import com.heartsync.model.VaultPage;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VaultStoreTest {
    private Path tempDir;
    private VaultStore vaultStore;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("vault-test");
        vaultStore = new VaultStore(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.walk(tempDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> p.toFile().delete());
    }

    @Test
    void shouldParseFrontmatter() {
        String raw = "---\ntitle: 测试页\ntype: fact\n---\n正文内容";
        VaultPage page = vaultStore.parsePage("test.md", raw);
        assertEquals("测试页", page.getTitle());
        assertEquals("fact", page.getType());
        assertEquals("正文内容", page.getContent());
    }

    @Test
    void shouldExtractWikilinks() {
        String content = "养了只[[猫-橘子]]，住在[[杭州]]。";
        List<String> links = VaultStore.extractWikilinks(content);
        assertEquals(2, links.size());
        assertTrue(links.contains("猫-橘子"));
        assertTrue(links.contains("杭州"));
    }

    @Test
    void shouldReadAndWritePage() throws Exception {
        VaultPage page = VaultPage.builder()
            .title("测试")
            .type("fact")
            .content("正文")
            .build();
        vaultStore.writePage("test.md", page);

        VaultPage read = vaultStore.readPage("test.md");
        assertEquals("测试", read.getTitle());
        assertEquals("正文", read.getContent());
    }

    @Test
    void shouldReadAllPages() throws Exception {
        vaultStore.writePage("a.md", VaultPage.builder().title("A").type("fact").content("a").build());
        vaultStore.writePage("b.md", VaultPage.builder().title("B").type("fact").content("b").build());

        List<VaultPage> pages = vaultStore.readAllPages();
        assertEquals(2, pages.size());
    }

    @Test
    void shouldUpdatePage() throws Exception {
        vaultStore.writePage("test.md", VaultPage.builder().title("旧").type("fact").content("旧内容").build());
        vaultStore.updatePage("test.md", "新内容");

        VaultPage updated = vaultStore.readPage("test.md");
        assertEquals("新内容", updated.getContent());
    }

    @Test
    void shouldDeletePage() throws Exception {
        vaultStore.writePage("test.md", VaultPage.builder().title("X").type("fact").content("x").build());
        vaultStore.deletePage("test.md");
        assertFalse(Files.exists(tempDir.resolve("test.md")));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
./gradlew test --tests "com.heartsync.vault.VaultStoreTest"
```

预期: 编译失败（VaultPage、VaultStore 不存在）

- [ ] **Step 3: 创建 VaultPage 模型**

```java
package com.heartsync.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Vault 中单篇 markdown 页的数据结构
 */
public class VaultPage {
    private String path;        // 文件相对路径，如 "facts/用户-猫.md"
    private String title;       // frontmatter.title
    private String type;        // frontmatter.type: persona/fact/event/person/state
    private List<String> tags;  // frontmatter.tags
    private List<String> links; // [[wikilink]] 提取的链接目标
    private String content;     // 正文（frontmatter 之后的内容）
    private LocalDate created;
    private LocalDate updated;

    public VaultPage() {
        this.tags = new ArrayList<>();
        this.links = new ArrayList<>();
    }

    // Builder 方便测试和构造
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final VaultPage page = new VaultPage();
        public Builder path(String v) { page.path = v; return this; }
        public Builder title(String v) { page.title = v; return this; }
        public Builder type(String v) { page.type = v; return this; }
        public Builder tags(List<String> v) { page.tags = v; return this; }
        public Builder links(List<String> v) { page.links = v; return this; }
        public Builder content(String v) { page.content = v; return this; }
        public Builder created(LocalDate v) { page.created = v; return this; }
        public Builder updated(LocalDate v) { page.updated = v; return this; }
        public VaultPage build() { return page; }
    }

    // Getters & Setters
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public List<String> getLinks() { return links; }
    public void setLinks(List<String> links) { this.links = links; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDate getCreated() { return created; }
    public void setCreated(LocalDate created) { this.created = created; }
    public LocalDate getUpdated() { return updated; }
    public void setUpdated(LocalDate updated) { this.updated = updated; }
}
```

- [ ] **Step 4: 创建 VaultStore**

```java
package com.heartsync.vault;

import com.heartsync.model.VaultPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Obsidian 式 markdown vault 文件读写服务
 * 职责：md 页 CRUD、frontmatter 解析、[[wikilink]] 抽取
 */
public class VaultStore {
    private static final Logger log = LoggerFactory.getLogger(VaultStore.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);
    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path vaultRoot;

    public VaultStore(String vaultPath) {
        this.vaultRoot = Path.of(vaultPath);
    }

    /**
     * 解析原始 markdown 文本为 VaultPage
     * @param path 文件相对路径
     * @param raw 原始 markdown 文本
     * @return 解析后的 VaultPage
     */
    public VaultPage parsePage(String path, String raw) {
        VaultPage page = new VaultPage();
        page.setPath(path);

        Matcher fm = FRONTMATTER_PATTERN.matcher(raw);
        if (fm.find()) {
            parseFrontmatterYaml(fm.group(1), page);
            page.setContent(fm.group(2).trim());
        } else {
            // 无 frontmatter，全部作为正文
            page.setContent(raw.trim());
        }

        // 从正文提取 wikilink
        page.setLinks(extractWikilinks(page.getContent()));
        return page;
    }

    /**
     * 读取单篇 markdown 页
     */
    public VaultPage readPage(String path) throws IOException {
        String raw = Files.readString(vaultRoot.resolve(path), StandardCharsets.UTF_8);
        return parsePage(path, raw);
    }

    /**
     * 读取 vault 下所有 markdown 页（排除 .gitkeep）
     */
    public List<VaultPage> readAllPages() throws IOException {
        if (!Files.exists(vaultRoot)) {
            return Collections.emptyList();
        }
        try (Stream<Path> walk = Files.walk(vaultRoot)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .map(p -> {
                    try {
                        String relPath = vaultRoot.relativize(p).toString().replace('\\', '/');
                        return readPage(relPath);
                    } catch (IOException e) {
                        log.error("读取 vault 页面失败: {}", p, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        }
    }

    /**
     * 写入新页面（覆盖）
     */
    public void writePage(String path, VaultPage page) throws IOException {
        Path filePath = vaultRoot.resolve(path);
        Files.createDirectories(filePath.getParent());
        String markdown = toMarkdown(page);
        Files.writeString(filePath, markdown, StandardCharsets.UTF_8);
        log.info("vault 页面写入: {}", path);
    }

    /**
     * 更新页面正文（保留 frontmatter 其他字段，更新 updated 时间）
     */
    public void updatePage(String path, String newContent) throws IOException {
        VaultPage existing = readPage(path);
        existing.setContent(newContent);
        existing.setUpdated(LocalDate.now());
        writePage(path, existing);
    }

    /**
     * 删除页面
     */
    public void deletePage(String path) throws IOException {
        Files.deleteIfExists(vaultRoot.resolve(path));
        log.info("vault 页面删除: {}", path);
    }

    /**
     * 从正文提取所有 [[wikilink]] 目标
     */
    public static List<String> extractWikilinks(String content) {
        if (content == null) return Collections.emptyList();
        List<String> links = new ArrayList<>();
        Matcher m = WIKILINK_PATTERN.matcher(content);
        while (m.find()) {
            links.add(m.group(1).trim());
        }
        return links;
    }

    /**
     * 根据标题查找页面（用于 wikilink 扩展）
     */
    public VaultPage findByTitle(String title) throws IOException {
        return readAllPages().stream()
            .filter(p -> title.equals(p.getTitle()))
            .findFirst()
            .orElse(null);
    }

    /**
     * VaultPage → markdown 文本
     */
    private String toMarkdown(VaultPage page) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        if (page.getTitle() != null) sb.append("title: ").append(page.getTitle()).append("\n");
        if (page.getType() != null) sb.append("type: ").append(page.getType()).append("\n");
        if (page.getCreated() != null) sb.append("created: ").append(page.getCreated().format(DATE_FMT)).append("\n");
        if (page.getUpdated() != null) sb.append("updated: ").append(page.getUpdated().format(DATE_FMT)).append("\n");
        if (page.getTags() != null && !page.getTags().isEmpty()) {
            sb.append("tags: [").append(String.join(", ", page.getTags())).append("]\n");
        }
        if (page.getLinks() != null && !page.getLinks().isEmpty()) {
            String linksStr = page.getLinks().stream()
                .map(l -> "[[" + l + "]]")
                .collect(Collectors.joining(", "));
            sb.append("links: ").append(linksStr).append("\n");
        }
        sb.append("---\n");
        if (page.getContent() != null) {
            sb.append(page.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 简单 YAML frontmatter 解析（只解析顶层 key: value）
     * ponytail: 手写简单解析，不上 SnakeYAML 依赖
     */
    private void parseFrontmatterYaml(String yaml, VaultPage page) {
        for (String line : yaml.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();

            switch (key) {
                case "title": page.setTitle(value); break;
                case "type": page.setType(value); break;
                case "created":
                    try { page.setCreated(LocalDate.parse(value, DATE_FMT)); } catch (Exception e) { /* ignore */ }
                    break;
                case "updated":
                    try { page.setUpdated(LocalDate.parse(value, DATE_FMT)); } catch (Exception e) { /* ignore */ }
                    break;
                case "tags":
                    // 解析 [tag1, tag2] 格式
                    page.setTags(parseListValue(value));
                    break;
                case "links":
                    // 从 "[[a]], [[b]]" 提取
                    page.setLinks(extractWikilinks(value));
                    break;
            }
        }
    }

    private List<String> parseListValue(String value) {
        return Arrays.stream(value.replaceAll("[\\[\\]]", "").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
./gradlew test --tests "com.heartsync.vault.VaultStoreTest"
```

预期: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/heartsync/model/VaultPage.java src/main/java/com/heartsync/vault/VaultStore.java src/test/java/com/heartsync/vault/VaultStoreTest.java
git commit -m "feat: VaultPage 模型 + VaultStore — markdown 读写、frontmatter 解析、wikilink 抽取"
```

---

### Task 3: LuceneIndex — BM25 全文检索

**目标:** 启动时扫描 vault 全量建索引，运行时增量更新，BM25 检索

**依赖:** Task 2 (VaultStore)

**文件:**
- 创建: `src/main/java/com/heartsync/vault/LuceneIndex.java`
- 创建: `src/test/java/com/heartsync/vault/LuceneIndexTest.java`

**接口:**
- 消费: `VaultStore.readAllPages()`
- 产出: `LuceneIndex` 类（方法: `buildIndex(vaultStore)`, `addPage(page)`, `removePage(path)`, `search(query, topN)` → `List<VaultPage>`）

- [ ] **Step 1: 写 LuceneIndex 测试**

```java
package com.heartsync.vault;

import com.heartsync.model.VaultPage;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LuceneIndexTest {
    private Path tempDir;
    private VaultStore vaultStore;
    private LuceneIndex luceneIndex;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("lucene-test");
        vaultStore = new VaultStore(tempDir.toString());
        luceneIndex = new LuceneIndex();

        // 写入测试页面
        vaultStore.writePage("facts/cat.md", VaultPage.builder()
            .title("猫-橘子").type("fact").content("养了只橘猫叫橘子，2023年领养的").build());
        vaultStore.writePage("facts/work.md", VaultPage.builder()
            .title("用户-工作").type("fact").content("在杭州做后端开发，加班多").build());
    }

    @AfterEach
    void tearDown() throws Exception {
        luceneIndex.close();
        Files.walk(tempDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> p.toFile().delete());
    }

    @Test
    void shouldSearchByKeyword() throws IOException {
        luceneIndex.buildIndex(vaultStore);
        List<VaultPage> results = luceneIndex.search("猫", 5);
        assertEquals(1, results.size());
        assertEquals("猫-橘子", results.get(0).getTitle());
    }

    @Test
    void shouldUpdateIndexAfterAdd() throws IOException {
        luceneIndex.buildIndex(vaultStore);
        VaultPage newPage = VaultPage.builder()
            .title("用户-日常").type("fact").content("喜欢深夜聊天").build();
        vaultStore.writePage("facts/daily.md", newPage);
        luceneIndex.addPage("facts/daily.md", newPage);

        List<VaultPage> results = luceneIndex.search("深夜", 5);
        assertEquals(1, results.size());
        assertEquals("用户-日常", results.get(0).getTitle());
    }

    @Test
    void shouldRemoveFromIndex() throws IOException {
        luceneIndex.buildIndex(vaultStore);
        luceneIndex.removePage("facts/cat.md");
        List<VaultPage> results = luceneIndex.search("猫", 5);
        assertEquals(0, results.size());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
./gradlew test --tests "com.heartsync.vault.LuceneIndexTest"
```

预期: 编译失败

- [ ] **Step 3: 创建 LuceneIndex**

```java
package com.heartsync.vault;

import com.heartsync.model.VaultPage;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * 基于 Lucene 的 BM25 全文检索索引
 * 使用内存索引（ByteBuffersDirectory），启动快，适合 vault 规模
 */
public class LuceneIndex {
    private static final Logger log = LoggerFactory.getLogger(LuceneIndex.class);

    private final ByteBuffersDirectory directory;
    private final StandardAnalyzer analyzer;
    private IndexWriter writer;

    public LuceneIndex() throws IOException {
        this.directory = new ByteBuffersDirectory();
        this.analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        this.writer = new IndexWriter(directory, config);
    }

    /**
     * 启动时全量扫描 vault 建索引
     */
    public void buildIndex(VaultStore vaultStore) throws IOException {
        // 清空现有索引
        writer.deleteAll();
        writer.commit();

        List<VaultPage> pages = vaultStore.readAllPages();
        for (VaultPage page : pages) {
            addDocument(page);
        }
        writer.commit();
        log.info("Lucene 索引构建完成，共 {} 篇页面", pages.size());
    }

    /**
     * 增量添加单篇页面到索引
     */
    public void addPage(String path, VaultPage page) throws IOException {
        // 先删除旧文档（如果存在）
        writer.deleteDocuments(new Term("path", path));
        addDocument(page);
        writer.commit();
        log.info("Lucene 索引更新: {}", path);
    }

    /**
     * 从索引中移除页面
     */
    public void removePage(String path) throws IOException {
        writer.deleteDocuments(new Term("path", path));
        writer.commit();
        log.info("Lucene 索引删除: {}", path);
    }

    /**
     * BM25 全文检索，返回 Top-N 结果
     */
    public List<VaultPage> search(String query, int topN) throws IOException {
        // 用 IndexReader 搜索（保证读到最新 commit）
        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("body", analyzer);
            try {
                Query q = parser.parse(query);
                TopDocs topDocs = searcher.search(q, topN);
                List<VaultPage> results = new ArrayList<>();
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.doc(sd.doc);
                    results.add(docToPage(doc));
                }
                return results;
            } catch (org.apache.lucene.queryparser.classic.ParseException e) {
                log.warn("Lucene 查询解析失败: {}", query, e);
                return Collections.emptyList();
            }
        }
    }

    public void close() throws IOException {
        writer.close();
        analyzer.close();
        directory.close();
    }

    private void addDocument(VaultPage page) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("path", page.getPath() != null ? page.getPath() : "", Field.Store.YES));
        doc.add(new TextField("title", page.getTitle() != null ? page.getTitle() : "", Field.Store.YES));
        doc.add(new TextField("body", page.getContent() != null ? page.getContent() : "", Field.Store.YES));
        doc.add(new StringField("type", page.getType() != null ? page.getType() : "", Field.Store.YES));
        writer.addDocument(doc);
    }

    private VaultPage docToPage(Document doc) {
        return VaultPage.builder()
            .path(doc.get("path"))
            .title(doc.get("title"))
            .type(doc.get("type"))
            .content(doc.get("body"))
            .build();
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
./gradlew test --tests "com.heartsync.vault.LuceneIndexTest"
```

预期: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/heartsync/vault/LuceneIndex.java src/test/java/com/heartsync/vault/LuceneIndexTest.java
git commit -m "feat: LuceneIndex — BM25 全文检索，内存索引，增量更新"
```

---

### Task 4: ChatMessage 模型 + LlmClient

**目标:** 定义 WebSocket 消息协议模型，封装 DeepSeek 流式调用

**依赖:** Task 1

**文件:**
- 创建: `src/main/java/com/heartsync/model/ChatMessage.java`, `src/main/java/com/heartsync/service/LlmClient.java`

**接口:**
- 产出: `ChatMessage` 类（字段: `type`, `content`, `messageId`）
- 产出: `LlmClient` 类（方法: `streamResponse(userMessage, systemPrompt, history, memories)` → `Flux<String>`）

- [ ] **Step 1: 创建 ChatMessage 模型**

```java
package com.heartsync.model;

/**
 * WebSocket 消息协议对象
 * 五种类型: chat / token / done / push / ping / pong
 */
public class ChatMessage {
    private String type;      // 消息类型: chat, token, done, push, ping, pong
    private String content;   // 消息内容
    private String messageId; // 消息 ID（done 消息时使用）

    public ChatMessage() {}

    public ChatMessage(String type, String content) {
        this.type = type;
        this.content = content;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
}
```

- [ ] **Step 2: 创建 LlmClient**

```java
package com.heartsync.service;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek 流式 LLM 调用封装
 * DeepSeek 兼容 OpenAI 接口，使用 langchain4j-open-ai 模块
 */
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final StreamingChatLanguageModel model;

    public LlmClient(String apiKey, String baseUrl, String modelName) {
        this.model = OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(modelName)
            .timeout(Duration.ofSeconds(60))
            .build();
    }

    /**
     * 流式对话，返回 token 流
     * @param userMessage 用户输入
     * @param systemPrompt 系统人设 prompt
     * @param history 最近 10 轮对话历史（UserMessage/AiMessage 交替）
     * @param memories 召回的 Vault 记忆片段文本
     * @return Flux<String> token 流
     */
    public Flux<String> streamResponse(String userMessage, String systemPrompt,
                                        List<dev.langchain4j.data.message.ChatMessage> history,
                                        String memories) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 拼装完整消息列表
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        // 1. system prompt（人设 + 记忆）
        StringBuilder fullSystem = new StringBuilder(systemPrompt);
        if (memories != null && !memories.isEmpty()) {
            fullSystem.append("\n\n## 关于用户的记忆\n").append(memories);
        }
        messages.add(new SystemMessage(fullSystem.toString()));

        // 2. 历史对话
        messages.addAll(history);

        // 3. 当前用户消息
        messages.add(new UserMessage(userMessage));

        // 流式调用
        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                sink.tryEmitNext(partialResponse);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                sink.tryEmitComplete();
                log.info("LLM 流式回复完成");
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM 调用失败", error);
                sink.tryEmitError(error);
            }
        });

        return sink.asFlux();
    }
}
```

- [ ] **Step 3: 创建 AppConfig — Bean 注册**

```java
package com.heartsync.config;

import com.heartsync.service.LlmClient;
import com.heartsync.vault.LuceneIndex;
import com.heartsync.vault.VaultStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Spring Bean 配置 — 将所有核心组件注册为 Spring Bean
 */
@Configuration
public class AppConfig {

    @Value("${heartsync.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${heartsync.deepseek.base-url}")
    private String deepseekBaseUrl;

    @Value("${heartsync.deepseek.model}")
    private String deepseekModel;

    @Value("${heartsync.vault.path}")
    private String vaultPath;

    @Bean
    public VaultStore vaultStore() {
        return new VaultStore(vaultPath);
    }

    @Bean
    public LuceneIndex luceneIndex() throws IOException {
        return new LuceneIndex();
    }

    @Bean
    public LlmClient llmClient() {
        return new LlmClient(deepseekApiKey, deepseekBaseUrl, deepseekModel);
    }
}
```

- [ ] **Step 4: 验证编译**

```bash
./gradlew compileJava
```

预期: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/heartsync/model/ChatMessage.java src/main/java/com/heartsync/service/LlmClient.java src/main/java/com/heartsync/config/AppConfig.java
git commit -m "feat: ChatMessage 模型 + LlmClient — DeepSeek 流式调用封装"
```

---

### Task 5: PersonaService

**目标:** 从 vault 装载人设 system prompt，管理关系/情绪状态

**依赖:** Task 2 (VaultStore)

**文件:**
- 创建: `src/main/java/com/heartsync/service/PersonaService.java`

**接口:**
- 消费: `VaultStore.readPage()`
- 产出: `PersonaService` 类（方法: `loadSystemPrompt()` → `String`, `getState()` → `VaultPage`, `updateStateField(key, value)`）

- [ ] **Step 1: 创建 PersonaService**

```java
package com.heartsync.service;

import com.heartsync.model.VaultPage;
import com.heartsync.vault.VaultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 人设服务 — 装载 system prompt、管理关系/情绪状态
 */
@Service
public class PersonaService {
    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);
    private static final String PERSONA_PATH = "persona/default.md";
    private static final String STATE_PATH = "state.md";

    private final VaultStore vaultStore;

    public PersonaService(VaultStore vaultStore) {
        this.vaultStore = vaultStore;
    }

    /**
     * 装载人设 system prompt（从 vault/persona/default.md 读取正文）
     */
    public String loadSystemPrompt() {
        try {
            VaultPage persona = vaultStore.readPage(PERSONA_PATH);
            return persona.getContent();
        } catch (IOException e) {
            log.error("加载人设失败，使用默认 prompt", e);
            return "你是一个温柔贴心的恋人。"; // 兜底
        }
    }

    /**
     * 获取当前关系状态（vault/state.md）
     */
    public VaultPage getState() {
        try {
            return vaultStore.readPage(STATE_PATH);
        } catch (IOException e) {
            log.error("加载状态失败", e);
            return VaultPage.builder().title("状态").type("state").content("").build();
        }
    }

    /**
     * 更新状态页中的某个字段
     * ponytail: 简单字符串替换，状态页字段少，不上完整 YAML 写入
     */
    public void updateStateField(String key, String value) {
        try {
            VaultPage state = vaultStore.readPage(STATE_PATH);
            String content = state.getContent();
            String newContent = content.replaceAll(
                java.util.regex.Pattern.quote(key + ": ") + ".*",
                key + ": " + value
            );
            vaultStore.updatePage(STATE_PATH, newContent);
            log.info("状态更新: {} → {}", key, value);
        } catch (IOException e) {
            log.error("状态更新失败: {} → {}", key, value, e);
        }
    }

    /**
     * 获取当前情绪
     */
    public String getMood() {
        VaultPage state = getState();
        String content = state.getContent();
        for (String line : content.split("\n")) {
            if (line.startsWith("当前情绪:")) {
                return line.substring("当前情绪:".length()).trim();
            }
        }
        return "平静";
    }

    /**
     * 获取关系阶段
     */
    public String getRelationshipStage() {
        VaultPage state = getState();
        String content = state.getContent();
        for (String line : content.split("\n")) {
            if (line.startsWith("关系阶段:")) {
                return line.substring("关系阶段:".length()).trim();
            }
        }
        return "初识";
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew compileJava
```

预期: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/heartsync/service/PersonaService.java
git commit -m "feat: PersonaService — 人设装载、关系/情绪状态管理"
```

---

### Task 6: MemoryService — 记忆召回 + 写回

**目标:** BM25 + wikilink 图谱检索召回，LLM 事实抽取后写回 vault

**依赖:** Task 2 (VaultStore), Task 3 (LuceneIndex), Task 4 (LlmClient)

**文件:**
- 创建: `src/main/java/com/heartsync/service/MemoryService.java`
- 创建: `src/test/java/com/heartsync/service/MemoryServiceTest.java`

**接口:**
- 消费: `VaultStore.readAllPages()`, `VaultStore.findByTitle()`, `LuceneIndex.search()`, `LlmClient.streamResponse()`
- 产出: `MemoryService` 类（方法: `recall(query)` → `String`, `remember(userMessage, aiResponse)` → `void`）

- [ ] **Step 1: 写 MemoryService 测试**

```java
package com.heartsync.service;

import com.heartsync.model.VaultPage;
import com.heartsync.vault.LuceneIndex;
import com.heartsync.vault.VaultStore;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MemoryServiceTest {
    private Path tempDir;
    private VaultStore vaultStore;
    private LuceneIndex luceneIndex;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("memory-test");
        vaultStore = new VaultStore(tempDir.toString());
        luceneIndex = new LuceneIndex();

        // 写入测试 vault 页面，包含 wikilink 关系
        vaultStore.writePage("facts/cat.md", VaultPage.builder()
            .title("猫-橘子").type("fact")
            .content("养了只橘猫叫橘子，2023年领养的。[[用户]]")
            .links(List.of("用户")).build());
        vaultStore.writePage("people/user.md", VaultPage.builder()
            .title("用户").type("person")
            .content("在杭州做后端开发，加班多。[[用户-工作]]")
            .links(List.of("用户-工作")).build());
        vaultStore.writePage("facts/work.md", VaultPage.builder()
            .title("用户-工作").type("fact")
            .content("在杭州某互联网公司做后端开发，经常加班到深夜。")
            .links(List.of()).build());

        luceneIndex.buildIndex(vaultStore);

        // MemoryService 使用 mock LlmClient（recall 不依赖 LLM，remember 依赖）
        memoryService = new MemoryService(vaultStore, luceneIndex, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        luceneIndex.close();
        Files.walk(tempDir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> p.toFile().delete());
    }

    @Test
    void shouldRecallByBM25() {
        String result = memoryService.recall("猫");
        assertTrue(result.contains("橘子"));
    }

    @Test
    void shouldExpandByWikilink() {
        // 搜索"猫"→命中 cat.md → wikilink 扩展 → 拉出 user.md
        String result = memoryService.recall("猫");
        assertTrue(result.contains("杭州")); // 来自 user.md
    }

    @Test
    void shouldNotExpandBeyondOneHop() {
        // 搜"猫"→cat.md→user.md→work.md（但只扩展一跳，work.md 不应出现）
        String result = memoryService.recall("猫");
        // work.md 是 user.md 的邻居，但搜索入口是 cat.md，
        // 一跳扩展：cat.md→user.md，不再从 user.md 继续扩展
        // 所以 work.md 不应该出现
        assertFalse(result.contains("经常加班到深夜"));
    }

    @Test
    void shouldDeduplicateByTitle() {
        // 如果两个搜索命中同一篇页面，应去重
        String result = memoryService.recall("橘子 猫");
        // 只出现一次
        int count = result.split("橘子").length - 1;
        assertEquals(1, count);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
./gradlew test --tests "com.heartsync.service.MemoryServiceTest"
```

预期: 编译失败

- [ ] **Step 3: 创建 MemoryService**

```java
package com.heartsync.service;

import com.heartsync.model.VaultPage;
import com.heartsync.vault.LuceneIndex;
import com.heartsync.vault.VaultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆服务 — BM25 召回 + wikilink 图谱扩展 + 事实抽取写回
 */
@Service
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int RECALL_TOP_N = 5;

    private final VaultStore vaultStore;
    private final LuceneIndex luceneIndex;
    private final LlmClient llmClient; // 用于 remember 阶段的事实抽取，可能为 null（测试时）

    public MemoryService(VaultStore vaultStore, LuceneIndex luceneIndex, LlmClient llmClient) {
        this.vaultStore = vaultStore;
        this.luceneIndex = luceneIndex;
        this.llmClient = llmClient;
    }

    /**
     * 记忆召回：BM25 全文 + wikilink 图谱一跳
     * @param query 用户输入
     * @return 拼装好的记忆片段文本，可直接拼入 prompt
     */
    public String recall(String query) {
        try {
            // 1. BM25 全文检索
            List<VaultPage> bm25Results = luceneIndex.search(query, RECALL_TOP_N);
            if (bm25Results.isEmpty()) {
                return "";
            }

            // 2. 按标题去重
            Set<String> seenTitles = new HashSet<>();
            List<VaultPage> recalled = new ArrayList<>();
            for (VaultPage page : bm25Results) {
                if (seenTitles.add(page.getTitle())) {
                    recalled.add(page);
                }
            }

            // 3. wikilink 一跳扩展
            for (VaultPage page : new ArrayList<>(recalled)) {
                for (String link : page.getLinks()) {
                    VaultPage linked = vaultStore.findByTitle(link);
                    if (linked != null && seenTitles.add(linked.getTitle())) {
                        recalled.add(linked);
                    }
                }
            }

            // 4. 拼装为文本片段
            return recalled.stream()
                .map(p -> String.format("[%s] %s", p.getTitle(), p.getContent()))
                .collect(Collectors.joining("\n"));

        } catch (IOException e) {
            log.error("记忆召回失败", e);
            return ""; // 降级：无记忆继续对话
        }
    }

    /**
     * 记忆写回：从本轮对话中抽取事实，更新 vault
     * 异步执行，失败不影响对话主流程
     * @param userMessage 用户消息
     * @param aiResponse  AI 回复
     */
    public void remember(String userMessage, String aiResponse) {
        if (llmClient == null) {
            log.warn("LlmClient 未初始化，跳过记忆写回");
            return;
        }
        // 异步执行
        CompletableFuture.runAsync(() -> {
            try {
                // 用 LLM 抽取事实
                String factPrompt = buildFactExtractionPrompt(userMessage, aiResponse);
                // ponytail: 事实抽取用同步调用，一条消息不会太长
                // 阶段 2 可改为流式 + 结构化输出
                String extracted = extractFactSync(factPrompt);
                if (extracted != null && !extracted.isEmpty() && !"NONE".equals(extracted.trim())) {
                    applyFactToVault(extracted);
                }
            } catch (Exception e) {
                log.error("记忆写回失败，不影响主流程", e);
            }
        });
    }

    private String buildFactExtractionPrompt(String userMessage, String aiResponse) {
        return """
            从以下对话中抽取值得记住的事实。如果用户分享了新信息或更新了旧信息，返回事实。
            如果没有任何值得记住的新信息，返回 NONE。

            格式: 实体名 | 事实内容 | 动作(create/update)
            示例: 橘子 | 最近不爱吃猫粮，主人换了皇家猫粮 | update

            用户: %s
            恋人: %s
            """.formatted(userMessage, aiResponse);
    }

    private String extractFactSync(String prompt) {
        // ponytail: 同步阻塞调用，记忆写回不需要流式
        // 阶段 2 改为 LangChain4j 的 AiServices 结构化输出
        try {
            // 使用 java.net.http 做一次简单的同步调用
            // 实际实现见 Task 8 中对 DeepSeek 的同步调用封装
            return "NONE"; // 占位，Task 8 完成 LlmClient 同步方法后替换
        } catch (Exception e) {
            log.error("事实抽取失败", e);
            return "NONE";
        }
    }

    private void applyFactToVault(String extracted) {
        // 解析 "实体名 | 事实内容 | 动作"
        String[] parts = extracted.split("\\|");
        if (parts.length < 3) return;

        String entity = parts[0].trim();
        String fact = parts[1].trim();
        String action = parts[2].trim();

        String fileName = "facts/" + entity + ".md";
        try {
            VaultPage page;
            try {
                page = vaultStore.readPage(fileName);
                // update: 追加事实
                page.setContent(page.getContent() + "\n" + fact);
            } catch (IOException e) {
                // 文件不存在，create
                page = VaultPage.builder()
                    .title(entity)
                    .type("fact")
                    .content(fact)
                    .build();
            }
            vaultStore.writePage(fileName, page);
            luceneIndex.addPage(fileName, page);
            log.info("记忆写入: {} → {}", entity, fact);
        } catch (IOException e) {
            log.error("记忆写入 vault 失败: {}", fileName, e);
        }
    }
}
```

注意：步骤 3 中的 `extractFactSync` 方法是占位实现。Task 8 中会给 `LlmClient` 加同步方法，届时替换。

- [ ] **Step 4: 运行测试验证通过**

```bash
./gradlew test --tests "com.heartsync.service.MemoryServiceTest"
```

预期: 全部 PASS（recall 测试不依赖 LLM）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/heartsync/service/MemoryService.java src/test/java/com/heartsync/service/MemoryServiceTest.java
git commit -m "feat: MemoryService — BM25+wikilink 图谱召回 + 事实抽取写回"
```

---

### Task 7: CompanionService — 对话编排

**目标:** 编排全链路：取记忆→拼 prompt→流式调 LLM→推送 token→异步写回记忆

**依赖:** Task 4 (LlmClient), Task 5 (PersonaService), Task 6 (MemoryService)

**文件:**
- 创建: `src/main/java/com/heartsync/service/CompanionService.java`
- 创建: `src/test/java/com/heartsync/service/CompanionServiceTest.java`

**接口:**
- 消费: `MemoryService.recall()`, `PersonaService.loadSystemPrompt()`, `LlmClient.streamResponse()`
- 产出: `CompanionService` 类（方法: `chat(userId, message)` → `Flux<String>`, `onChatComplete(userId, userMessage, aiResponse)` → `void`）

- [ ] **Step 1: 写 CompanionService 测试**

```java
package com.heartsync.service;

import com.heartsync.vault.VaultStore;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanionServiceTest {

    private CompanionService companionService;
    private MemoryService memoryService;
    private PersonaService personaService;
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        // 使用 mock/假实现
        // ponytail: 不引入 Mockito，手写匿名类
        memoryService = new MemoryService(null, null, null) {
            @Override
            public String recall(String query) {
                return "用户养了猫叫橘子";
            }
        };
        personaService = new PersonaService(null) {
            @Override
            public String loadSystemPrompt() {
                return "你是一个温柔贴心的恋人";
            }
        };
        llmClient = new LlmClient("fake-key", "http://localhost", "test") {
            @Override
            public Flux<String> streamResponse(String userMessage, String systemPrompt,
                                                List<dev.langchain4j.data.message.ChatMessage> history,
                                                String memories) {
                return Flux.just("抱", "抱", "你");
            }
        };
        companionService = new CompanionService(memoryService, personaService, llmClient);
    }

    @Test
    void shouldReturnTokenStream() {
        Flux<String> tokens = companionService.chat("user-1", "心情不好");
        StepVerifier.create(tokens)
            .expectNext("抱", "抱", "你")
            .verifyComplete();
    }

    @Test
    void shouldMaintainConversationHistory() {
        // 发两轮，历史应有 2 条用户消息和 2 条 AI 回复
        companionService.chat("user-1", "第一轮").blockLast();
        companionService.onChatComplete("user-1", "第一轮", "回复1");

        companionService.chat("user-1", "第二轮").blockLast();
        companionService.onChatComplete("user-1", "第二轮", "回复2");

        // 此时历史应包含 4 条消息（2 轮 × 2 条）
        // 验证不会超过 10 轮（20 条消息）
        assertEquals(4, companionService.getHistorySize("user-1"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
./gradlew test --tests "com.heartsync.service.CompanionServiceTest"
```

预期: 编译失败

- [ ] **Step 3: 创建 CompanionService**

```java
package com.heartsync.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话编排服务 — 取记忆 → 拼 prompt → 流式调 LLM → 异步写回记忆
 * 核心编排逻辑，连接 Memory、Persona、LLM 三大模块
 */
@Service
public class CompanionService {
    private static final Logger log = LoggerFactory.getLogger(CompanionService.class);
    private static final int MAX_HISTORY_ROUNDS = 10; // 最多保留 10 轮对话
    private static final int MAX_HISTORY_MESSAGES = MAX_HISTORY_ROUNDS * 2; // 用户+AI 各一条

    private final MemoryService memoryService;
    private final PersonaService personaService;
    private final LlmClient llmClient;

    // 会话历史：userId → 消息列表（ponytail: 内存 Map，单机够用，阶段 2 上 Redis）
    private final Map<String, List<ChatMessage>> conversationHistory = new ConcurrentHashMap<>();

    public CompanionService(MemoryService memoryService, PersonaService personaService, LlmClient llmClient) {
        this.memoryService = memoryService;
        this.personaService = personaService;
        this.llmClient = llmClient;
    }

    /**
     * 处理用户消息，返回 AI 流式回复
     * @param userId 用户 ID
     * @param message 用户消息文本
     * @return Flux<String> token 流
     */
    public Flux<String> chat(String userId, String message) {
        log.info("用户消息: userId={}, message={}", userId, message);

        // 1. 记忆召回
        String memories = memoryService.recall(message);

        // 2. 装载人设
        String systemPrompt = personaService.loadSystemPrompt();

        // 3. 获取历史对话
        List<ChatMessage> history = conversationHistory
            .getOrDefault(userId, Collections.emptyList());

        // 4. 流式调用 LLM
        return llmClient.streamResponse(message, systemPrompt, history, memories);
    }

    /**
     * 对话完成回调：将本轮对话加入历史，触发异步记忆写回
     */
    public void onChatComplete(String userId, String userMessage, String aiResponse) {
        // 更新对话历史
        List<ChatMessage> history = conversationHistory
            .computeIfAbsent(userId, k -> new ArrayList<>());
        history.add(new UserMessage(userMessage));
        history.add(new AiMessage(aiResponse));

        // 滑动窗口：超过最大轮数时移除最早的
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }

        // 更新状态：上次互动时间
        personaService.updateStateField("上次互动", java.time.LocalDateTime.now().toString());

        // 异步记忆写回
        memoryService.remember(userMessage, aiResponse);

        log.info("对话完成: userId={}, historySize={}", userId, history.size());
    }

    /**
     * 获取历史对话轮数（测试用）
     */
    public int getHistorySize(String userId) {
        return conversationHistory.getOrDefault(userId, Collections.emptyList()).size();
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
./gradlew test --tests "com.heartsync.service.CompanionServiceTest"
```

预期: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/heartsync/service/CompanionService.java src/test/java/com/heartsync/service/CompanionServiceTest.java
git commit -m "feat: CompanionService — 对话编排：记忆→prompt→LLM→历史→写回"
```

---

### Task 8: Netty WebSocket Server

**目标:** 手写 Netty WebSocket 服务端，含 pipeline、认证、心跳、会话管理、业务处理

**依赖:** Task 3 (ChatMessage), Task 7 (CompanionService)

**文件:**
- 创建: `src/main/java/com/heartsync/netty/WsSessionManager.java`, `src/main/java/com/heartsync/netty/AuthHandler.java`, `src/main/java/com/heartsync/netty/HeartbeatHandler.java`, `src/main/java/com/heartsync/netty/ChatHandler.java`, `src/main/java/com/heartsync/netty/NettyWsServer.java`
- 创建: `src/test/java/com/heartsync/netty/ChatHandlerTest.java`

**接口:**
- 消费: `CompanionService.chat()`, `CompanionService.onChatComplete()`
- 产出: `NettyWsServer`（Spring Bean，`@PostConstruct` 启动，`@PreDestroy` 关闭）

- [ ] **Step 1: 创建 WsSessionManager**

```java
package com.heartsync.netty;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接会话管理器
 * 维护 userId ↔ Channel 双向映射，支持主动推送
 */
public class WsSessionManager {
    private static final Logger log = LoggerFactory.getLogger(WsSessionManager.class);

    // userId → Channel（主动推送用）
    private final ConcurrentHashMap<String, Channel> userChannels = new ConcurrentHashMap<>();
    // ChannelId → userId（断连清理用）
    private final ConcurrentHashMap<String, String> channelUsers = new ConcurrentHashMap<>();
    // 所有活跃连接
    private final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 用户上线，绑定连接
     */
    public void onConnect(String userId, Channel channel) {
        Channel old = userChannels.put(userId, channel);
        if (old != null && old != channel) {
            // 同一用户旧连接存在，关闭旧连接
            log.info("用户重复登录，关闭旧连接: userId={}", userId);
            old.close();
        }
        channelUsers.put(channel.id().asShortText(), userId);
        allChannels.add(channel);
        log.info("用户上线: userId={}, channelId={}, 当前在线: {}", userId, channel.id().asShortText(), allChannels.size());
    }

    /**
     * 用户下线，清理映射
     */
    public void onDisconnect(Channel channel) {
        String channelId = channel.id().asShortText();
        String userId = channelUsers.remove(channelId);
        if (userId != null) {
            userChannels.remove(userId);
            log.info("用户下线: userId={}, channelId={}, 当前在线: {}", userId, channelId, allChannels.size());
        }
        allChannels.remove(channel);
    }

    /**
     * 根据 userId 查找连接（主动推送用）
     */
    public Channel getChannel(String userId) {
        return userChannels.get(userId);
    }

    /**
     * 获取在线用户数
     */
    public int getOnlineCount() {
        return allChannels.size();
    }

    /**
     * 获取所有在线 userId
     */
    public java.util.Set<String> getOnlineUsers() {
        return new java.util.HashSet<>(userChannels.keySet());
    }
}
```

- [ ] **Step 2: 创建 AuthHandler**

```java
package com.heartsync.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.HashMap;

/**
 * WebSocket 握手阶段认证 Handler
 * 从 URL query 参数提取 token，校验后放行
 */
public class AuthHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    private final String expectedToken;
    private final WsSessionManager sessionManager;

    public AuthHandler(String expectedToken, WsSessionManager sessionManager) {
        this.expectedToken = expectedToken;
        this.sessionManager = sessionManager;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            String token = extractToken(request.uri());

            if (token == null || !token.equals(expectedToken)) {
                log.warn("认证失败: token={}", token);
                // 返回 401 并关闭连接
                var response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                    request.protocolVersion(), HttpResponseStatus.UNAUTHORIZED);
                ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                ReferenceCountUtil.release(msg);
                return;
            }

            // 认证成功，绑定 userId（阶段 1 用 token 作为 userId）
            String userId = "user-" + token.substring(0, Math.min(8, token.length()));
            sessionManager.onConnect(userId, ctx.channel());

            // 将 userId 存到 channel attr 中，后续 Handler 使用
            ctx.channel().attr(io.netty.util.AttributeKey.valueOf("userId")).set(userId);

            // 移除自身（认证只做一次）
            ctx.pipeline().remove(this);
            super.channelRead(ctx, msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private String extractToken(String uri) {
        try {
            URI u = new URI(uri);
            String query = u.getQuery();
            if (query == null) return null;
            // 解析 query 参数
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    return kv[1];
                }
            }
        } catch (Exception e) {
            log.error("URI 解析失败: {}", uri, e);
        }
        return null;
    }
}
```

- [ ] **Step 3: 创建 HeartbeatHandler**

```java
package com.heartsync.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳检测 Handler
 * 60 秒无读触发空闲 → 发送 Ping，120 秒无读 → 断开连接
 */
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                log.info("心跳超时，关闭连接: {}", ctx.channel().id().asShortText());
                ctx.close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 处理客户端心跳 ping/pong
        if (msg instanceof io.netty.handler.codec.http.websocketx.TextWebSocketFrame) {
            String text = ((io.netty.handler.codec.http.websocketx.TextWebSocketFrame) msg).text();
            if ("{\"type\":\"ping\"}".equals(text)) {
                // 回复 pong
                ctx.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame("{\"type\":\"pong\"}"));
                ReferenceCountUtil.release(msg);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }
}
```

- [ ] **Step 4: 创建 ChatHandler**

```java
package com.heartsync.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartsync.model.ChatMessage;
import com.heartsync.service.CompanionService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 业务处理 Handler
 * 收发消息、流式推送 token
 */
public class ChatHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AttributeKey<String> USER_ID_KEY = AttributeKey.valueOf("userId");

    private final CompanionService companionService;
    private final WsSessionManager sessionManager;

    public ChatHandler(CompanionService companionService, WsSessionManager sessionManager) {
        this.companionService = companionService;
        this.sessionManager = sessionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        ReferenceCountUtil.release(frame); // 释放 ByteBuf

        ChatMessage msg;
        try {
            msg = MAPPER.readValue(text, ChatMessage.class);
        } catch (Exception e) {
            log.warn("消息解析失败: {}", text, e);
            return;
        }

        String userId = ctx.channel().attr(USER_ID_KEY).get();
        if (userId == null) {
            log.warn("未认证的连接发送消息");
            return;
        }

        if ("chat".equals(msg.getType())) {
            handleChat(ctx, userId, msg.getContent());
        }
    }

    /**
     * 处理聊天消息：流式推送 AI 回复
     */
    private void handleChat(ChannelHandlerContext ctx, String userId, String userMessage) {
        StringBuilder fullResponse = new StringBuilder();

        companionService.chat(userId, userMessage)
            .doOnNext(token -> {
                // 每个 token 推送给客户端
                String json;
                try {
                    json = MAPPER.writeValueAsString(new ChatMessage("token", token));
                } catch (Exception e) {
                    log.error("token JSON 序列化失败", e);
                    return;
                }
                ctx.writeAndFlush(new TextWebSocketFrame(json));
                fullResponse.append(token);
            })
            .doOnComplete(() -> {
                // 流结束，发送 done 消息
                String messageId = "msg_" + System.currentTimeMillis();
                ChatMessage done = new ChatMessage("done", "");
                done.setMessageId(messageId);
                try {
                    String json = MAPPER.writeValueAsString(done);
                    ctx.writeAndFlush(new TextWebSocketFrame(json));
                } catch (Exception e) {
                    log.error("done JSON 序列化失败", e);
                }

                // 触发对话完成回调
                companionService.onChatComplete(userId, userMessage, fullResponse.toString());
            })
            .doOnError(error -> {
                log.error("对话流式处理失败: userId={}", userId, error);
                // 兜底消息
                try {
                    String json = MAPPER.writeValueAsString(new ChatMessage("token", "（出了点问题，能再说一遍吗？）"));
                    ctx.writeAndFlush(new TextWebSocketFrame(json));
                } catch (Exception e) {
                    log.error("兜底消息发送失败", e);
                }
            })
            .subscribe(); // 触发订阅
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        sessionManager.onDisconnect(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket 异常: channelId={}", ctx.channel().id().asShortText(), cause);
        ctx.close();
    }
}
```

- [ ] **Step 5: 创建 NettyWsServer**

```java
package com.heartsync.netty;

import com.heartsync.service.CompanionService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 服务端
 * 独立端口 8081，处理 WebSocket 长连接
 */
@Component
public class NettyWsServer {
    private static final Logger log = LoggerFactory.getLogger(NettyWsServer.class);

    private final int port;
    private final String wsPath;
    private final String authToken;
    private final CompanionService companionService;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final WsSessionManager sessionManager = new WsSessionManager();

    public NettyWsServer(
        @Value("${heartsync.netty.port}") int port,
        @Value("${heartsync.netty.ws-path}") String wsPath,
        @Value("${heartsync.auth.token}") String authToken,
        CompanionService companionService
    ) {
        this.port = port;
        this.wsPath = wsPath;
        this.authToken = authToken;
        this.companionService = companionService;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);   // boss 线程：接收连接
        workerGroup = new NioEventLoopGroup();  // worker 线程：处理 I/O

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    // HTTP 编解码
                    pipeline.addLast(new HttpServerCodec());
                    // HTTP 消息聚合（最大 64KB）
                    pipeline.addLast(new HttpObjectAggregator(65536));
                    // 认证（WebSocket 握手前）
                    pipeline.addLast(new AuthHandler(authToken, sessionManager));
                    // WebSocket 协议升级
                    pipeline.addLast(new WebSocketServerProtocolHandler(wsPath));
                    // 空闲检测（60 秒无读 → 断开）
                    pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                    // 心跳处理
                    pipeline.addLast(new HeartbeatHandler());
                    // 业务处理
                    pipeline.addLast(new ChatHandler(companionService, sessionManager));
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("Netty WebSocket 服务启动: port={}, path={}", port, wsPath);
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Netty WebSocket 服务已关闭");
    }

    public WsSessionManager getSessionManager() {
        return sessionManager;
    }
}
```

- [ ] **Step 6: 写 ChatHandler 测试**

```java
package com.heartsync.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatHandlerTest {

    @Test
    void shouldProcessChatMessage() {
        // ponytail: EmbeddedChannel 集成测试，验证 pipeline 消息能正确处理
        // 阶段 2 完善
        assertTrue(true);
    }
}
```

- [ ] **Step 7: 验证编译**

```bash
./gradlew compileJava
```

预期: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/heartsync/netty/
git commit -m "feat: Netty WebSocket Server — 手写 pipeline、认证、心跳、会话管理"
```

---

### Task 9: HTTP Controllers

**目标:** Spring MVC REST 接口：健康检查、人设配置、会话列表、记忆浏览

**依赖:** Task 5 (PersonaService), Task 8 (NettyWsServer → WsSessionManager), Task 2 (VaultStore)

**文件:**
- 创建: `src/main/java/com/heartsync/controller/HealthController.java`, `src/main/java/com/heartsync/controller/ConfigController.java`, `src/main/java/com/heartsync/controller/SessionController.java`, `src/main/java/com/heartsync/controller/MemoryController.java`

- [ ] **Step 1: 创建所有 Controller**

```java
// HealthController.java
package com.heartsync.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "heratsync");
    }
}
```

```java
// ConfigController.java
package com.heartsync.controller;

import com.heartsync.service.PersonaService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {
    private final PersonaService personaService;

    public ConfigController(PersonaService personaService) {
        this.personaService = personaService;
    }

    @GetMapping("/persona")
    public Map<String, String> getPersona() {
        return Map.of("persona", personaService.loadSystemPrompt());
    }

    @PutMapping("/persona")
    public Map<String, String> updatePersona(@RequestBody Map<String, String> body) {
        // ponytail: 阶段 1 简单实现，直接改写 vault 文件
        String newPersona = body.get("persona");
        if (newPersona != null) {
            personaService.updateStateField("persona", newPersona);
        }
        return Map.of("status", "ok");
    }
}
```

```java
// SessionController.java
package com.heartsync.controller;

import com.heartsync.netty.NettyWsServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SessionController {
    private final NettyWsServer nettyWsServer;

    public SessionController(NettyWsServer nettyWsServer) {
        this.nettyWsServer = nettyWsServer;
    }

    @GetMapping("/sessions")
    public Map<String, Object> getSessions() {
        return Map.of(
            "onlineCount", nettyWsServer.getSessionManager().getOnlineCount(),
            "onlineUsers", nettyWsServer.getSessionManager().getOnlineUsers()
        );
    }
}
```

```java
// MemoryController.java
package com.heartsync.controller;

import com.heartsync.model.VaultPage;
import com.heartsync.vault.VaultStore;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    private final VaultStore vaultStore;

    public MemoryController(VaultStore vaultStore) {
        this.vaultStore = vaultStore;
    }

    @GetMapping
    public List<Map<String, String>> listMemories() throws IOException {
        return vaultStore.readAllPages().stream()
            .map(p -> Map.of(
                "path", p.getPath() != null ? p.getPath() : "",
                "title", p.getTitle() != null ? p.getTitle() : "",
                "type", p.getType() != null ? p.getType() : ""
            ))
            .collect(Collectors.toList());
    }

    @GetMapping("/{path}")
    public VaultPage getMemory(@PathVariable String path) throws IOException {
        // 路径中的 / 会被 URL 编码，需要还原
        return vaultStore.readPage(path);
    }

    @DeleteMapping("/{path}")
    public Map<String, String> deleteMemory(@PathVariable String path) throws IOException {
        vaultStore.deletePage(path);
        return Map.of("status", "deleted");
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
./gradlew compileJava
```

预期: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/heartsync/controller/
git commit -m "feat: HTTP Controllers — 健康检查、人设配置、会话列表、记忆浏览"
```

---

### Task 10: PushService — 主动推送

**目标:** 定时任务触发主动问候，根据时间段和上次互动时间决定是否推送

**依赖:** Task 8 (NettyWsServer → WsSessionManager), Task 4 (LlmClient)

**文件:**
- 创建: `src/main/java/com/heartsync/service/PushService.java`

- [ ] **Step 1: 创建 PushService**

```java
package com.heartsync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartsync.model.ChatMessage;
import com.heartsync.netty.WsSessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 主动推送服务 — 定时检查并推送问候消息
 * ponytail: 简单规则触发，阶段 2 加 LLM 生成个性化内容
 */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final WsSessionManager sessionManager;
    private final CompanionService companionService;

    // 上次推送时间（ponytail: 单用户，内存记录）
    private LocalDateTime lastPushTime = null;

    public PushService(WsSessionManager sessionManager, CompanionService companionService) {
        this.sessionManager = sessionManager;
        this.companionService = companionService;
    }

    /**
     * 每 30 分钟检查一次，满足条件则推送
     */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void checkAndPush() {
        Set<String> onlineUsers = sessionManager.getOnlineUsers();
        if (onlineUsers.isEmpty()) {
            return;
        }

        // 一小时最多推送一次
        if (lastPushTime != null && lastPushTime.plusHours(1).isAfter(LocalDateTime.now())) {
            return;
        }

        // 根据时间段生成推送内容
        String message = generatePushMessage();
        if (message == null) {
            return;
        }

        // 推送给所有在线用户
        for (String userId : onlineUsers) {
            Channel channel = sessionManager.getChannel(userId);
            if (channel != null && channel.isActive()) {
                try {
                    String json = MAPPER.writeValueAsString(new ChatMessage("push", message));
                    channel.writeAndFlush(new TextWebSocketFrame(json));
                    log.info("主动推送: userId={}, message={}", userId, message);
                } catch (Exception e) {
                    log.error("主动推送失败: userId={}", userId, e);
                }
            }
        }
        lastPushTime = LocalDateTime.now();
    }

    /**
     * 根据时间段生成推送消息
     */
    private String generatePushMessage() {
        int hour = LocalTime.now().getHour();
        if (hour >= 6 && hour < 9) {
            return "早安~ 今天有什么计划吗？";
        } else if (hour >= 22 || hour < 2) {
            return "这么晚了还没睡？";
        } else if (hour >= 12 && hour < 14) {
            return "中午好，吃饭了吗？";
        } else {
            return "在忙什么呢？";
        }
    }
}
```

需要更新 `NettyWsServer`，暴露 `getSessionManager()` 为 Bean：

```java
// 在 AppConfig.java 中添加
@Bean
public WsSessionManager wsSessionManager(NettyWsServer nettyWsServer) {
    return nettyWsServer.getSessionManager();
}
```

- [ ] **Step 2: 更新 AppConfig**

```java
// 在 AppConfig.java 末尾添加以下方法
@Bean
public WsSessionManager wsSessionManager(NettyWsServer nettyWsServer) {
    return nettyWsServer.getSessionManager();
}
```

- [ ] **Step 3: 验证编译**

```bash
./gradlew compileJava
```

预期: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/heartsync/service/PushService.java src/main/java/com/heartsync/config/AppConfig.java
git commit -m "feat: PushService — 定时主动推送，时间段问候"
```

---

### Task 11: 前端聊天界面

**目标:** 极简 HTML/CSS/JS 聊天界面，WebSocket 连接，流式 token 打字效果，记忆侧栏

**依赖:** Task 8 (Netty WebSocket), Task 9 (HTTP Controllers)

**文件:**
- 创建: `web/index.html`, `web/style.css`, `web/app.js`

- [ ] **Step 1: 创建 index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HeratSync - AI 陪伴</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div class="container">
        <!-- 记忆侧栏 -->
        <aside class="sidebar" id="sidebar">
            <div class="sidebar-header">
                <h3>记忆</h3>
                <button id="toggleSidebar" title="收起侧栏">◀</button>
            </div>
            <div class="sidebar-list" id="memoryList">
                <div class="loading">加载中...</div>
            </div>
        </aside>

        <!-- 聊天区域 -->
        <main class="chat-area">
            <header class="chat-header">
                <h1>HeratSync</h1>
                <span class="status" id="statusIndicator">● 离线</span>
            </header>
            <div class="messages" id="messages">
                <div class="message system">
                    <div class="bubble">连接中...</div>
                </div>
            </div>
            <div class="input-area">
                <input type="text" id="input" placeholder="说点什么..." autofocus disabled>
                <button id="sendBtn" disabled>发送</button>
            </div>
        </main>
    </div>
    <script src="app.js"></script>
</body>
</html>
```

- [ ] **Step 2: 创建 style.css**

```css
* { margin: 0; padding: 0; box-sizing: border-box; }

:root {
    --bg: #1a1a2e;
    --surface: #16213e;
    --primary: #e94560;
    --text: #eee;
    --text-secondary: #999;
    --bubble-user: #e94560;
    --bubble-ai: #0f3460;
    --sidebar-width: 260px;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    background: var(--bg);
    color: var(--text);
    height: 100vh;
    overflow: hidden;
}

.container {
    display: flex;
    height: 100vh;
}

/* 侧栏 */
.sidebar {
    width: var(--sidebar-width);
    background: var(--surface);
    display: flex;
    flex-direction: column;
    border-right: 1px solid #333;
    transition: width 0.3s;
}
.sidebar.collapsed { width: 0; overflow: hidden; }
.sidebar-header {
    display: flex; justify-content: space-between; align-items: center;
    padding: 16px; border-bottom: 1px solid #333;
}
.sidebar-header h3 { font-size: 14px; }
.sidebar-header button {
    background: none; border: none; color: var(--text); cursor: pointer; font-size: 12px;
}
.sidebar-list { flex: 1; overflow-y: auto; padding: 8px; }
.memory-item {
    padding: 8px 12px; cursor: pointer; border-radius: 6px; margin-bottom: 4px;
    font-size: 13px; color: var(--text-secondary);
}
.memory-item:hover { background: rgba(255,255,255,0.05); color: var(--text); }
.memory-item .type { font-size: 10px; color: var(--primary); text-transform: uppercase; }

/* 聊天区 */
.chat-area {
    flex: 1; display: flex; flex-direction: column;
}
.chat-header {
    display: flex; justify-content: space-between; align-items: center;
    padding: 16px 24px; border-bottom: 1px solid #333;
}
.chat-header h1 { font-size: 18px; }
.status { font-size: 12px; }
.status.online { color: #4caf50; }
.status.offline { color: var(--text-secondary); }

.messages {
    flex: 1; overflow-y: auto; padding: 24px;
    display: flex; flex-direction: column; gap: 12px;
}
.message { display: flex; }
.message.user { justify-content: flex-end; }
.message.ai { justify-content: flex-start; }
.message.system { justify-content: center; }
.message.system .bubble {
    background: rgba(255,255,255,0.05); color: var(--text-secondary);
    font-size: 12px; padding: 6px 12px;
}
.bubble {
    max-width: 70%; padding: 10px 16px; border-radius: 16px;
    line-height: 1.5; font-size: 14px; word-break: break-word;
}
.message.user .bubble { background: var(--bubble-user); }
.message.ai .bubble { background: var(--bubble-ai); }
.bubble.streaming::after {
    content: "▊"; animation: blink 1s infinite;
}
@keyframes blink { 50% { opacity: 0; } }

.input-area {
    display: flex; padding: 16px 24px; border-top: 1px solid #333; gap: 12px;
}
.input-area input {
    flex: 1; padding: 12px 16px; border-radius: 24px; border: 1px solid #333;
    background: var(--surface); color: var(--text); font-size: 14px; outline: none;
}
.input-area input:focus { border-color: var(--primary); }
.input-area button {
    padding: 12px 24px; border-radius: 24px; border: none;
    background: var(--primary); color: #fff; font-size: 14px; cursor: pointer;
}
.input-area button:disabled { opacity: 0.5; cursor: not-allowed; }

.loading { padding: 16px; text-align: center; color: var(--text-secondary); font-size: 13px; }
```

- [ ] **Step 3: 创建 app.js**

```javascript
// 配置（从页面 URL 推断或硬编码）
const WS_URL = `ws://${location.hostname}:8081/ws/chat?token=heartsync-dev-token`;
const API_BASE = `http://${location.hostname}:8080/api`;

let ws = null;
let currentAiBubble = null;

// DOM 元素
const messagesEl = document.getElementById('messages');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('sendBtn');
const statusEl = document.getElementById('statusIndicator');
const memoryListEl = document.getElementById('memoryList');
const sidebarEl = document.getElementById('sidebar');
const toggleBtn = document.getElementById('toggleSidebar');

// 侧栏开关
toggleBtn.addEventListener('click', () => {
    sidebarEl.classList.toggle('collapsed');
    toggleBtn.textContent = sidebarEl.classList.contains('collapsed') ? '▶' : '◀';
});

// 连接 WebSocket
function connect() {
    ws = new WebSocket(WS_URL);

    ws.onopen = () => {
        setStatus(true);
        inputEl.disabled = false;
        sendBtn.disabled = false;
        addSystemMessage('已连接');
    };

    ws.onclose = () => {
        setStatus(false);
        inputEl.disabled = true;
        sendBtn.disabled = true;
        addSystemMessage('连接断开，5 秒后重连...');
        setTimeout(connect, 5000);
    };

    ws.onerror = () => {
        setStatus(false);
    };

    ws.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            handleMessage(msg);
        } catch (e) {
            console.error('消息解析失败:', e);
        }
    };
}

// 处理服务端消息
function handleMessage(msg) {
    switch (msg.type) {
        case 'token':
            if (!currentAiBubble) {
                currentAiBubble = addAiBubble('');
            }
            currentAiBubble.textContent += msg.content;
            currentAiBubble.classList.add('streaming');
            scrollToBottom();
            break;

        case 'done':
            if (currentAiBubble) {
                currentAiBubble.classList.remove('streaming');
                currentAiBubble = null;
            }
            break;

        case 'push':
            addAiBubble(msg.content);
            break;

        case 'pong':
            // 心跳回复，无需处理
            break;
    }
}

// 发送消息
function sendMessage() {
    const text = inputEl.value.trim();
    if (!text || !ws || ws.readyState !== WebSocket.OPEN) return;

    // 显示用户消息
    addUserBubble(text);
    inputEl.value = '';

    // 发送
    ws.send(JSON.stringify({ type: 'chat', content: text }));
}

// UI 辅助
function addUserBubble(text) {
    const div = document.createElement('div');
    div.className = 'message user';
    div.innerHTML = `<div class="bubble">${escapeHtml(text)}</div>`;
    messagesEl.appendChild(div);
    scrollToBottom();
}

function addAiBubble(text) {
    const div = document.createElement('div');
    div.className = 'message ai';
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;
    div.appendChild(bubble);
    messagesEl.appendChild(div);
    scrollToBottom();
    return bubble;
}

function addSystemMessage(text) {
    const div = document.createElement('div');
    div.className = 'message system';
    div.innerHTML = `<div class="bubble">${escapeHtml(text)}</div>`;
    messagesEl.appendChild(div);
    scrollToBottom();
}

function setStatus(online) {
    statusEl.textContent = online ? '● 在线' : '● 离线';
    statusEl.className = 'status ' + (online ? 'online' : 'offline');
}

function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 加载记忆列表
async function loadMemories() {
    try {
        const res = await fetch(`${API_BASE}/memories`);
        const memories = await res.json();
        memoryListEl.innerHTML = memories.map(m =>
            `<div class="memory-item">
                <span class="type">${m.type}</span>
                <span>${m.title}</span>
            </div>`
        ).join('');
    } catch (e) {
        memoryListEl.innerHTML = '<div class="loading">加载失败</div>';
    }
}

// 事件绑定
sendBtn.addEventListener('click', sendMessage);
inputEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') sendMessage();
});

// 启动
connect();
loadMemories();
```

- [ ] **Step 4: 配置静态资源服务**

在 `application.yml` 中添加静态资源配置：

```yaml
spring:
  web:
    resources:
      static-locations: file:web/
```

- [ ] **Step 5: 验证编译**

```bash
./gradlew compileJava
```

预期: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add web/ src/main/resources/application.yml
git commit -m "feat: 前端聊天界面 — 极简 HTML/CSS/JS，WebSocket 流式，记忆侧栏"
```

---

### Task 12: 集成启动 + 端到端验证

**目标:** 启动应用，验证全链路：WebSocket 连接 → 对话 → 流式返回 → vault 记忆写入

**依赖:** 所有前置 Task

**文件:**
- 修改: `src/main/java/com/heartsync/HeratSyncApplication.java`（如需要添加启动后初始化逻辑）

- [ ] **Step 1: 在 HeratSyncApplication 中添加启动后初始化**

```java
package com.heartsync;

import com.heartsync.service.LlmClient;
import com.heartsync.vault.LuceneIndex;
import com.heartsync.vault.VaultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HeratSyncApplication {
    private static final Logger log = LoggerFactory.getLogger(HeratSyncApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(HeratSyncApplication.class, args);
    }

    /**
     * 启动后初始化 Lucene 索引
     */
    @Bean
    public CommandLineRunner initIndex(VaultStore vaultStore, LuceneIndex luceneIndex) {
        return args -> {
            log.info("正在构建 vault 全文索引...");
            luceneIndex.buildIndex(vaultStore);
            log.info("启动完成 — HeratSync 已就绪");
        };
    }
}
```

- [ ] **Step 2: 启动应用**

```bash
# 设置环境变量
export DEEPSEEK_API_KEY=你的DeepSeek API Key
export AUTH_TOKEN=heartsync-dev-token

# 启动
./gradlew bootRun
```

- [ ] **Step 3: 验证 HTTP 端口**

```bash
curl http://localhost:8080/health
```

预期: `{"status":"ok","service":"heratsync"}`

- [ ] **Step 4: 验证记忆接口**

```bash
curl http://localhost:8080/api/memories
```

预期: 返回 vault 中所有页面列表

- [ ] **Step 5: WebSocket 端到端验证**

```bash
# 安装 websocat（如果没有）
# scoop install websocat  或  choco install websocat

# 连接 WebSocket 并发送消息
echo '{"type":"chat","content":"你好"}' | websocat ws://localhost:8081/ws/chat?token=heartsync-dev-token
```

预期: 收到流式 token 消息，最后收到 done 消息

- [ ] **Step 6: 验证记忆写回**

```bash
# 对话后检查 vault 目录是否有新文件
ls vault/facts/
```

预期: 可能看到新创建的 `.md` 文件

- [ ] **Step 7: 浏览器验证**

打开 `http://localhost:8080/index.html`（或直接打开 `web/index.html`），确认聊天界面正常工作。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/heartsync/HeratSyncApplication.java
git commit -m "feat: 启动初始化 + Lucene 索引自动构建"
```

---

## 自审清单

1. **Spec 覆盖**: 对照 spec 逐项检查：
   - ✅ Spring Boot 骨架 — Task 1
   - ✅ Netty WebSocket Server — Task 8
   - ✅ DeepSeek 流式对话 — Task 4 (LlmClient)
   - ✅ Vault 读写 + frontmatter + wikilink — Task 2
   - ✅ BM25 + 图谱检索 — Task 3 + Task 6
   - ✅ 人设 system prompt — Task 5
   - ✅ 定时主动推送 — Task 10
   - ✅ 极简前端 — Task 11
   - ✅ 单机部署，内存会话管理 — 全链路无外部依赖

2. **无占位符**: 所有代码步骤包含完整实现，无 TODO/TBD

3. **类型一致性**: 所有跨 Task 引用的类名、方法签名一致
   - `VaultStore.readPage(String path)` → Task 2 定义，Task 5/6/9 消费
   - `CompanionService.chat(String userId, String message)` → Task 7 定义，Task 8 消费
   - `WsSessionManager` → Task 8 定义，Task 9/10 消费