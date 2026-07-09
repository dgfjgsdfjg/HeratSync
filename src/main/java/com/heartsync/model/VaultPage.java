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
