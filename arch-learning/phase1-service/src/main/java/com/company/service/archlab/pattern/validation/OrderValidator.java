package com.company.service.archlab.pattern.validation;

/**
 * 责任链节点（责任链模式）。
 *
 * <p>约定：校验不通过就抛 {@code BizException}，一旦抛出，后面的节点不再执行（短路）。
 * 这样每个节点只关心一条规则，新增规则不用改老代码，也不会出现"漏调某个校验"。
 */
public interface OrderValidator {

    /** 节点名，用于回显"这次到底跑了哪些校验"，排障时比日志好查。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** 校验失败必须抛 BizException(ErrorCode, 原因)。 */
    void validate(OrderContext context);
}
