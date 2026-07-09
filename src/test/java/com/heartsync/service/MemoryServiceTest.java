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
        // 搜索"猫"->命中 cat.md -> wikilink 扩展 -> 拉出 user.md
        String result = memoryService.recall("猫");
        assertTrue(result.contains("杭州")); // 来自 user.md
    }

    @Test
    void shouldNotExpandBeyondOneHop() {
        // 搜"猫"->cat.md->user.md->work.md（但只扩展一跳，work.md 不应出现）
        String result = memoryService.recall("猫");
        // work.md 是 user.md 的邻居，但搜索入口是 cat.md，
        // 一跳扩展：cat.md->user.md，不再从 user.md 继续扩展
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
