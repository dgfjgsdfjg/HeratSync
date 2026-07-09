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
