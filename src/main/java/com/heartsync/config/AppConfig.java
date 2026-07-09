package com.heartsync.config;

import com.heartsync.service.LlmClient;
import com.heartsync.vault.LuceneIndex;
import com.heartsync.vault.VaultStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Spring Bean 配置 - 将所有核心组件注册为 Spring Bean
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
