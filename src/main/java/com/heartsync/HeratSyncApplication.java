package com.heartsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HeratSync 启动类 — AI 陪伴恋人应用
 */
@SpringBootApplication
@EnableScheduling  // 定时主动推送
public class HeratSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeratSyncApplication.class, args);
    }
}