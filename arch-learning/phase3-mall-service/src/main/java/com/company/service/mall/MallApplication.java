package com.company.service.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商城服务入口。
 *
 * <p>这条链路要回答的是"跨服务写操作怎么才能最终一致"：
 * 下单先 TCC 预占库存，支付后 Confirm，取消/超时 Cancel；
 * 订单状态变更与消息在同一张本地消息表里落库，再由中继异步投递；
 * 支付结果对商户走最大努力通知，消费端按消息 ID 幂等。
 */
@SpringBootApplication(scanBasePackages = {
        "com.company.service.mall",
        // 分布式事务对照实验（AT / TCC / Saga）随商城一起启动
        "com.company.service.archlab.scenario.distributedtx"
})
@EnableScheduling
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
