package com.company.learning.phase2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阶段二入口：缓存 + 中间件 + 各种锁。
 *
 * <p>【解决什么问题】阶段一的实验都在单进程内存里，回答不了多实例共享状态的问题：
 * 缓存穿透/击穿/雪崩会把数据库打垮，JVM 内的 {@code synchronized} 锁不住别的实例，
 * 重复请求会造成重复扣款，固定窗口限流还能被窗口边界绕过。这里把这些问题各做成一个可运行接口，
 * 用数字复现，而不是只背概念。
 *
 * <p>【为什么这样设计】默认全部用内存实现（内存版 RemoteCache + 本地锁），
 * 不接 Nacos、不依赖 Redis/RabbitMQ，{@code mvn spring-boot:run} 就能跑；
 * 想验证真实中间件时只改 application.yml 里的锁 provider / 缓存 remote，实验代码一行不动。
 * 扫描范围显式列出三个包，避免以后新增的实验包被静默带进阶段二，造成阶段间互相污染。
 *
 * <p>【怎么读这段代码】先看 {@code scanBasePackages} 里的三个包，再进
 * {@code controller/CacheController} 与 {@code controller/ScenarioController} 找接口；
 * 每个实验的逻辑分别在 {@code cache/CacheLab} 与 {@code scenario/**} 对应类里。
 * 启动后打开 http://localhost:8202/phase2/doc.html 直接点调试。
 */
@SpringBootApplication(scanBasePackages = {
        "com.company.service.archlab.cache",
        "com.company.service.archlab.scenario",
        "com.company.service.archlab.controller"
})
public class Phase2Application {

    private static final Logger log = LoggerFactory.getLogger(Phase2Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Phase2Application.class, args);
        // 用 slf4j 而不是控制台直接打印：启动标记也要有级别、时间戳，能被日志采集统一收走
        log.info("【启动】阶段二服务就绪，端口 8202，调试入口 http://localhost:8202/phase2/doc.html");
    }
}
