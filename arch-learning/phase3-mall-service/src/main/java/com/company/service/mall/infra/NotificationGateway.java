package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

/**
 * 外部通知出口（HTTP 回调 / 短信 / 商户网关）。失败即抛异常，由通知任务重试。
 */
public interface NotificationGateway {

    void notify(NotifyTask task);

    String provider();
}
