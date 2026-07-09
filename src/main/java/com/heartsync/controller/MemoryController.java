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

    @GetMapping("/{*path}")
    public VaultPage getMemory(@PathVariable String path) throws IOException {
        // {*path} 匹配多层路径（如 facts/user.md），Spring 传入时带前导 /
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return vaultStore.readPage(normalized);
    }

    @DeleteMapping("/{*path}")
    public Map<String, String> deleteMemory(@PathVariable String path) throws IOException {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        vaultStore.deletePage(normalized);
        return Map.of("status", "deleted");
    }
}
