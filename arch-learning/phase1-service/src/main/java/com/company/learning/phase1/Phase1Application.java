package com.company.learning.phase1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阶段一入口：多线程 + 设计模式。
 *
 * <p>启动后访问 http://localhost:8201/phase1/...。
 * 这里只放学习实验，不放业务逻辑。
 */
@SpringBootApplication(scanBasePackages = {
        "com.company.service.archlab.concurrency",
        "com.company.service.archlab.pattern",
        "com.company.service.archlab.controller"
})
public class Phase1Application {

    public static void main(String[] args) {
        SpringApplication.run(Phase1Application.class, args);
    }
}
