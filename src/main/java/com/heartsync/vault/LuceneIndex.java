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
