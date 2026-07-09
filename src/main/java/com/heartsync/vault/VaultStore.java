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
     * VaultPage -> markdown 文本
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
