package com.company.service.archlab.pattern.validation;

/**
 * 责任链节点（责任链模式）。
 *
 * <p>【解决什么问题】下单前的校验会越加越多：用户是否封禁、数量是否超限、库存是否充足、
 * 优惠券是否可用……如果全部写在一个方法里，规则之间无法复用，也无法回答"这次到底跑了哪几步"。
 *
 * <p>【为什么这样设计】每个节点只实现一条规则，失败就抛异常，链路自然短路；
 * 编排顺序交给 Spring 的 {@code @Order}，而不是写死在某个方法里。
 * 与"一个方法五段 if"相比：新增规则是新增类（不改老代码、不冲突），
 * 并且可以按需注入依赖（库存服务、缓存），老写法里这些依赖会把方法签名越撑越大。
 *
 * <p>【怎么读这段代码】记住两个约定即可：
 * <ul>
 *   <li>{@code name()} 决定节点在回显与排障里的名字；</li>
 *   <li>{@code validate()} 不通过就抛 {@code BizException}，不返回 false，避免调用方"忘了看返回值"。</li>
 * </ul>
 * 然后去 {@link ValidationChain} 看链路怎么把节点串起来。
 */
public interface OrderValidator {

    /** 节点名，用于回显"这次到底跑了哪些校验"，排障时比日志好查。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 校验失败必须抛 BizException(ErrorCode, 原因)。
     *
     * @param context 下单上下文，节点只读取自己关心的字段
     * @throws com.company.common.core.BizException 规则不满足时抛出，链路在此短路
     */
    void validate(OrderContext context);
}
