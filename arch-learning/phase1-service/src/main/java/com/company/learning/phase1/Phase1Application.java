package com.company.learning.phase1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阶段一入口：多线程 + 设计模式。
 *
 * <p>【解决什么问题】把阶段一的实验（JMM、计数器、锁、生产者-消费者、虚拟线程、三种设计模式）
 * 装进一个可独立启动的进程，不依赖 Nacos、不依赖中间件，`mvn spring-boot:run` 就能跑。
 *
 * <p>【为什么这样设计】扫描范围显式列出三个包而不是用默认的整包扫描：
 * 这样即使以后有人在 com.company.service.archlab 下加别的实验包，也不会被静默带进阶段一，
 * 避免阶段一/阶段二互相污染。
 *
 * <p>【怎么读这段代码】先看 {@code scanBasePackages} 里的三个包名，再进
 * {@code controller/ConcurrencyController} 与 {@code controller/PatternController} 找接口，
 * 实验逻辑都在对应的 Service 里。
 */
@SpringBootApplication(scanBasePackages = {
        "com.company.service.archlab.concurrency",
        "com.company.service.archlab.pattern",
        "com.company.service.archlab.controller"
})
public class Phase1Application {

    private static final Logger log = LoggerFactory.getLogger(Phase1Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Phase1Application.class, args);
        // 用 slf4j 而不是 System.out：System.out 没有级别、没有时间戳、不受 logback 配置控制，
        // 也没法在线上关掉；启动标记这类信息必须走同一套日志体系才能被采集
        log.info("【启动】阶段一服务就绪，端口 8201，调试入口 http://localhost:8201/phase1/doc.html");
    }
}
