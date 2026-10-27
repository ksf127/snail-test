package com.company.service.shortlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 短链服务入口。
 *
 * <p>读多写极少的典型形态：发号是低频写，跳转是超高 QPS 读。
 * 因此优化的核心不在数据库，而在"一次请求要经过几层、每层命中率多少"。
 */
@SpringBootApplication
@EnableScheduling
public class ShortlinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortlinkApplication.class, args);
    }
}
