package com.company.tradejournal.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动同步配置。
 *
 * <p>【解决什么问题】券商网页没有开放接口，不可能直接登录抓取；可行且稳定的做法是
 * 用户在券商端「一键导出」，系统定时扫描下载目录并自动入库。这样不用每天手工上传。
 *
 * <p>【怎么配】在 application.yml 里写 {@code trade-journal.sync.*}；
 * 不配目录时默认扫 {@code ~/Downloads}，改券商客户端下载位置时再加一项即可。
 */
@Data
@Component
@ConfigurationProperties(prefix = "trade-journal.sync")
public class SyncProperties {

    /** 是否启用定时扫描；关掉后只能手动触发。 */
    private boolean enabled = true;

    /** 要扫描的目录，支持多个；为空时用系统下载目录。 */
    private List<String> directories = new ArrayList<>();

    /** 自动导入落到哪个账户。 */
    private long accountId = 1L;

    /** 流水没有理由时写入的默认理由，便于在统计里筛出自动同步的记录。 */
    private String defaultReason = "自动同步";

    /** 只处理最近多少分钟内修改过的文件，避免首次启动把历史导出全部导入。 */
    private int maxAgeMinutes = 720;

    /** 单次扫描最多处理多少个新文件。 */
    private int maxFiles = 50;

    /** 状态接口返回最近多少条记录。 */
    private int recentLimit = 20;
}
