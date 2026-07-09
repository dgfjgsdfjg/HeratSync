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
