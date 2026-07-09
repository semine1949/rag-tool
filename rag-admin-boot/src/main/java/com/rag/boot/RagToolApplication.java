package com.rag.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * RAG向量入库工具 - 启动类
 */
@SpringBootApplication
@EnableAsync
public class RagToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagToolApplication.class, args);
    }
}
