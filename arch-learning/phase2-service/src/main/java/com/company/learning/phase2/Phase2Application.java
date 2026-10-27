package com.company.learning.phase2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阶段二入口：缓存 + 中间件 + 各种锁。
 *
 * <p>启动后访问 http://localhost:8202/phase2/...。
 * 默认全内存实现，把 application.yml 里的锁 provider / 缓存 remote 改成 redis 即可切到真实中间件。
 */
@SpringBootApplication(scanBasePackages = {
        "com.company.service.archlab.cache",
        "com.company.service.archlab.scenario",
        "com.company.service.archlab.controller"
})
public class Phase2Application {

    public static void main(String[] args) {
        SpringApplication.run(Phase2Application.class, args);
    }
}
