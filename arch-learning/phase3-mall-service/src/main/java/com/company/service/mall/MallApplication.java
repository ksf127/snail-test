package com.company.service.mall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商城服务入口。
 *
 * <p>【解决什么问题】这条链路要回答的是"跨服务写操作怎么才能最终一致"。
 * 一次下单同时涉及订单、库存、支付、消息、外部通知五方，任何一方失败都不能让其余几方
 * 留下不可回收的中间状态。
 *
 * <p>【为什么这样设计】三套机制按可靠性等级分工，不混用：
 * <ul>
 *   <li><b>TCC</b>：库存是可预占资源，下单先 Try 冻结、支付后 Confirm、取消/超时 Cancel</li>
 *   <li><b>Outbox</b>：订单状态变更与消息写进同一张本地消息表，同一事务落库，
 *       再由中继异步投递，避免"库提交了但消息没发出去"</li>
 *   <li><b>最大努力通知</b>：支付结果对商户尽力重试 N 次，失败进死信，靠对方主动查询兜底</li>
 * </ul>
 * 之所以不全部用 TCC 或全部用 outbox，是因为它们的代价不同：
 * TCC 侵入性强但隔离性好，outbox 只解决"消息不丢"不解决"资源不超卖"。
 *
 * <p>【怎么读这段代码】启动后先看 {@code /stats} 了解当前 provider（内存还是 RabbitMQ），
 * 再走 {@code POST /orders} → {@code POST /orders/{no}/pay}，
 * 最后用 {@code /ops/outbox/relay}、{@code /ops/notify} 手动推动中继，观察状态如何收敛。
 * 消费端按消息 ID 幂等，因此重复投递不会产生重复业务效果。
 */
@SpringBootApplication(scanBasePackages = {
        "com.company.service.mall",
        // 分布式事务对照实验（AT / TCC / Saga）随商城一起启动
        "com.company.service.archlab.scenario.distributedtx"
})
@EnableScheduling
public class MallApplication {

    private static final Logger log = LoggerFactory.getLogger(MallApplication.class);

    public static void main(String[] args) {
        log.info("【商城服务】启动中: 内存 Broker/通知渠道为默认实现，切换真实 MQ 请设置 company.mall.outbox.transport=rabbit");
        SpringApplication.run(MallApplication.class, args);
        log.info("【商城服务】启动完成");
    }
}
