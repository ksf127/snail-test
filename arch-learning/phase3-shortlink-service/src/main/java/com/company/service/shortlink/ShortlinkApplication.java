package com.company.service.shortlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 短链服务入口。
 *
 * <p>【解决什么问题】短链的每一层都靠 {@code shortlink.*} 配置在内存与 Redis 之间切换，
 * 点击增量还要按秒批量落盘。没有这个启动类，配置绑定、Bean 装配和定时任务都不会生效，
 * 服务直接起不来；即便强行 new 出组件，也会因为缺少统一配置而把单机实现带上生产。
 *
 * <p>【为什么这样设计】读多写极少的典型形态：发号是低频写，跳转是超高 QPS 读。
 * 因此优化的核心不在数据库，而在「一次请求要经过几层、每层命中率多少」。
 * 开启 {@code @EnableScheduling} 是为了让 {@code ClickRecorder} 能按秒把
 * 内存里的点击增量批量落盘 —— 这是主链路零 IO 的前提。
 *
 * <p>【怎么读这段代码】入口只有 {@link #main(String[])} 一行；
 * 真正的组件装配在 {@code support/ShortlinkConfiguration}，参数在
 * {@code support/ShortlinkProperties}，建议按这个顺序继续读。
 */
@SpringBootApplication
@EnableScheduling
public class ShortlinkApplication {

    private static final Logger log = LoggerFactory.getLogger(ShortlinkApplication.class);

    public static void main(String[] args) {
        log.info("【短链-启动】短链服务启动中：目标形态为读多写少，跳转走 布隆→L1→L2→存储 四级漏斗");
        SpringApplication.run(ShortlinkApplication.class, args);
    }
}
