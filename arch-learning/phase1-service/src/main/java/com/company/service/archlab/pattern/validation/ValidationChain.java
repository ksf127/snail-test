package com.company.service.archlab.pattern.validation;

import java.util.ArrayList;
import java.util.List;

import com.company.common.core.BizException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 校验链：Spring 会按 {@code @Order} 把全部 {@link OrderValidator} 注入成有序 List，
 * 这里只负责按顺序执行 + 记录执行轨迹。
 *
 * <p>【解决什么问题】有了单个校验节点之后，还需要一个"编排者"来决定谁先谁后、
 * 失败时怎么短路、以及怎么告诉调用方"跑到哪一步挂的"。
 *
 * <p>【为什么这样设计】为什么用 List 注入而不是自己 new：
 * 节点由容器管理，需要数据库/缓存/配置时直接注入依赖；
 * 新增校验只要加一个 {@code @Component}，链路自动生效。
 * 顺序交给 {@code @Order} 而不是硬编码在链里，这样"调整顺序"是改注解而不是改逻辑。
 * <p>实现上只做顺序执行，不缓存结果、不并发执行：先做零成本的本地校验，
 * 再打下游（库存），顺序本身就是一种性能优化，并发跑反而会把下游压力放大。
 *
 * <p>【怎么读这段代码】{@link #validate(OrderContext)} 的 for 循环就是全部逻辑；
 * 特别注意 for 里没有 try/catch 吞异常——节点一抛，循环立即中断，这就是"短路"。
 * 想确认链路顺序，用 {@code GET /patterns/validation/chain}。
 */
@Service
public class ValidationChain {

    private static final Logger log = LoggerFactory.getLogger(ValidationChain.class);

    private final List<OrderValidator> validators;

    public ValidationChain(List<OrderValidator> validators) {
        // List.copyOf 生成不可变副本：既隔离容器内部列表，也避免运行期被意外增删节点
        this.validators = List.copyOf(validators);
        log.info("【校验链】装配完成，共 {} 个节点，执行顺序: {}", validators.size(),
                validators.stream().map(OrderValidator::name).toList());
    }

    /**
     * 依次执行全部校验节点，遇到第一个失败即中断。
     *
     * @param context 下单上下文，非空
     * @return 实际执行过的节点名（按执行顺序），用于回显与排障；
     *         全部通过时长度等于节点总数
     * @throws BizException 任一节点校验失败时抛出（携带该节点的错误码与原因），后续节点不再执行
     */
    public List<String> validate(OrderContext context) {
        List<String> executed = new ArrayList<>(validators.size());
        log.info("【校验链】开始校验，userId={}，skuId={}，quantity={}",
                context.userId(), context.skuId(), context.quantity());
        for (OrderValidator validator : validators) {
            try {
                validator.validate(context);
            } catch (BizException e) {
                // 记下"挂在哪个节点、已经跑过哪些节点"，再原样抛出；
                // 这里只加日志不改异常，异常码和栈信息必须保留给上层统一处理
                log.warn("【校验链】节点 {} 校验失败，已执行节点={}，原因={}",
                        validator.name(), executed, e.getMessage());
                throw e;
            }
            executed.add(validator.name());
            // 节点级日志用 debug：校验是每次下单都走的必经路径，info 会把正常请求刷成噪音
            log.debug("【校验链】节点 {} 通过", validator.name());
        }
        log.info("【校验链】全部通过，执行节点={}", executed);
        return executed;
    }

    /**
     * 查看链路装配顺序，不执行业务校验。
     *
     * @return 节点名列表，顺序即实际执行顺序
     */
    public List<String> chain() {
        return validators.stream().map(OrderValidator::name).toList();
    }
}
