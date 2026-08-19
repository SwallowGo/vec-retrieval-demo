package com.twelvetimers.vector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 12Timers —— 文本模拟向量化与向量检索微服务。
 *
 * <p>异步向量化引擎使用自建线程池 + 阻塞队列（见 commit 3），
 * 不依赖任何队列中间件。
 */
@SpringBootApplication
public class VectorSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(VectorSearchApplication.class, args);
    }
}
