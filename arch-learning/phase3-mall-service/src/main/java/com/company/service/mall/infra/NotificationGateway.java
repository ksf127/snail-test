package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

/**
 * 外部通知出口（HTTP 回调 / 短信 / 商户网关）。失败即抛异常，由通知任务重试。
 *
 * <p>【解决什么问题】支付结果必须送到商户手里，但商户系统既慢又不可靠。
 * 把"怎么发"抽成接口，业务层只负责生成任务，发送失败由通知中继统一重试。
 *
 * <p>【为什么这样设计】约定"失败必须抛异常"，而不是返回 false：
 * 通知中继靠异常来判断该退避还是该标记成功，返回值被忽略的失败会被静默吞掉。
 * 同时该接口不能假设下游幂等，因此上层必须按 paymentNo 保证同一任务只成功一次。
 *
 * <p>【怎么读这段代码】{@link #notify} 正常返回 = 标记 SUCCEEDED；抛异常 = 退避重试；
 * {@link #provider} 用于日志与排障。
 */
public interface NotificationGateway {

    void notify(NotifyTask task);

    String provider();
}
