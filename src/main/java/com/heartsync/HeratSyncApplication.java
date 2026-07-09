package com.heartsync;

import com.heartsync.service.LlmClient;
import com.heartsync.vault.LuceneIndex;
import com.heartsync.vault.VaultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HeratSyncApplication {
    private static final Logger log = LoggerFactory.getLogger(HeratSyncApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(HeratSyncApplication.class, args);
    }

    /**
     * 启动后初始化 Lucene 索引
     */
    @Bean
    public CommandLineRunner initIndex(VaultStore vaultStore, LuceneIndex luceneIndex) {
        return args -> {
            log.info("正在构建 vault 全文索引...");
            luceneIndex.buildIndex(vaultStore);
            log.info("启动完成 - HeratSync 已就绪");
        };
    }
}
