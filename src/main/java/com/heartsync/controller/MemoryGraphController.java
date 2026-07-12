package com.heartsync.controller;

import com.heartsync.model.EventEntity;
import com.heartsync.model.VaultPage;
import com.heartsync.service.EventStore;
import com.heartsync.vault.VaultStore;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/**
 * 记忆球图数据接口：把 vault 事实页 + 事件组装成节点/连线，供前端 vis-network 渲染。
 * 结构：实体页=中心节点，每条事实=叶子节点连到实体；wikilink=实体间连线；事件=独立簇。
 */
@RestController
@RequestMapping("/api/memory-graph")
public class MemoryGraphController {

    private final VaultStore vaultStore;
    private final EventStore eventStore;

    public MemoryGraphController(VaultStore vaultStore, EventStore eventStore) {
        this.vaultStore = vaultStore;
        this.eventStore = eventStore;
    }

    @GetMapping
    public Map<String, Object> graph(@RequestParam(defaultValue = "user-heartsyn") String userId) throws IOException {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        List<VaultPage> pages = vaultStore.readAllPages();
        Set<String> titles = new HashSet<>();
        for (VaultPage p : pages) {
            if (p.getTitle() != null) titles.add(p.getTitle());
        }

        Set<String> seenHubs = new HashSet<>();   // 防止同名标题(如 people/user 与 facts/用户)重复建节点
        int[] leafSeq = {0};                        // 叶子全局唯一计数
        for (VaultPage p : pages) {
            if (p.getTitle() == null) continue;
            String hubId = "page:" + p.getTitle();
            boolean newHub = seenHubs.add(hubId);
            if (newHub) {
                String group = groupOf(p.getTitle(), p.getType());
                Map<String, Object> hub = node(hubId, p.getTitle(), group, 28);
                // 把文件路径带进节点数据，前端编辑时用于 API 调用
                if (p.getPath() != null) hub.put("data_path", p.getPath());
                nodes.add(hub);
            }

            // 每条事实一个叶子节点（ID 用全局计数保证唯一）
            if (p.getContent() != null) {
                for (String line : p.getContent().split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    String leafId = "leaf:" + (leafSeq[0]++);
                    nodes.add(node(leafId, truncate(t, 18), "detail", 10));
                    edges.add(edge(hubId, leafId));
                }
            }

            // wikilink → 实体间连线
            if (p.getLinks() != null) {
                for (String link : p.getLinks()) {
                    if (titles.contains(link)) {
                        edges.add(edge(hubId, "page:" + link));
                    }
                }
            }
        }

        // 事件簇
        List<EventEntity> events = eventStore.recent(userId, 20);
        for (EventEntity e : events) {
            String evId = "event:" + e.getId();
            nodes.add(node(evId, e.getEventDate() + " " + truncate(e.getTitle(), 12), "event", 16));
            // 事件挂到「用户」中心（若存在）
            if (titles.contains("用户")) {
                edges.add(edge("page:用户", evId));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    private Map<String, Object> node(String id, String label, String group, int size) {
        Map<String, Object> n = new HashMap<>();
        n.put("id", id);
        n.put("label", label);
        n.put("group", group);
        n.put("size", size);
        return n;
    }

    private Map<String, Object> edge(String from, String to) {
        Map<String, Object> e = new HashMap<>();
        e.put("from", from);
        e.put("to", to);
        return e;
    }

    /** 按实体名/类型给分组（前端按 group 上色） */
    private String groupOf(String title, String type) {
        if ("用户".equals(title)) return "user";
        if ("恋人".equals(title)) return "lover";
        if ("persona".equals(type)) return "persona";
        if ("state".equals(type)) return "state";
        return "other"; // 宠物、第三方
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
