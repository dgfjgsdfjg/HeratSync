package com.heartsync.service;

import com.heartsync.model.VaultPage;
import com.heartsync.vault.VaultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 人设服务 - 装载 system prompt、管理关系/情绪状态
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
            log.info("状态更新: {} -> {}", key, value);
        } catch (IOException e) {
            log.error("状态更新失败: {} -> {}", key, value, e);
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
