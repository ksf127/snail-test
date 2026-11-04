package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 用户状态校验：非空且未被封禁。
 * {@code @Order} 决定链路顺序，数值越小越先执行。
 *
 * <p>【解决什么问题】封禁用户必须在下单入口就被挡住，而不是等到支付或发货时才发现。
 * 这条规则在多个入口（下单、改单、支付回调）都要用，写在某个 Service 里就注定被复制。
 *
 * <p>【为什么这样设计】排在第一位（{@code @Order(10)}）：它是纯内存判断，零成本，
 * 先执行的另一个好处是——后续节点打印日志时可以假定 userId 非空，不用到处判空。
 * 不用这个模式会怎样：这条 if 会被复制到每个订单入口，改规则时漏改一处就是资损或合规问题。
 *
 * <p>【怎么读这段代码】两个判断：空值 → 40001，blocked 前缀 → 403。
 * 用前缀模拟"封禁名单"是为了让实验可单机运行，真实实现会查用户中心或缓存。
 */
@Component
@Order(10)
public class UserActiveValidator implements OrderValidator {

    private static final Logger log = LoggerFactory.getLogger(UserActiveValidator.class);

    /**
     * 校验用户是否可下单。
     *
     * @param context 下单上下文，读取 userId
     * @throws BizException userId 为空（40001）或用户被封禁（403）时抛出
     */
    @Override
    public void validate(OrderContext context) {
        if (context.userId() == null || context.userId().isBlank()) {
            // 失败分支用 warn 并带上关键变量，否则线上只能看到"参数错误"却不知道是哪个用户
            log.warn("【用户校验】userId 为空，拒绝下单");
            throw new BizException(ErrorCode.PARAM_INVALID, "userId 不能为空");
        }
        if (context.userId().startsWith("blocked")) {
            log.warn("【用户校验】userId={} 已被封禁，拒绝下单", context.userId());
            throw new BizException(ErrorCode.FORBIDDEN, "用户已被封禁: " + context.userId());
        }
        log.debug("【用户校验】通过，userId={}", context.userId());
    }
}
