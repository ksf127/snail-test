package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 反面教材：把三条规则塞进一个方法。
 *
 * <p>【解决什么问题】它不解决新问题——代表"没有责任链时最自然的写法"，
 * 用来和 {@link ValidationChain} 对照：同样的入参，结果一样，但可知性与扩展性完全不同。
 *
 * <p>【为什么这样设计（其实是"为什么不要这样写"）】它的演进终点很固定：
 * 今天加"限购"，明天加"黑名单"，后天加"活动资格"，
 * 最后这个方法 300 行、十几个 if、每个 if 里还有嵌套，没人敢动，也没法单独测。
 * 具体缺陷：
 * <ul>
 *   <li>没有任何"执行到哪一步"的信息，只能靠异常信息猜；</li>
 *   <li>规则无法复用，别的入口要用只能复制粘贴；</li>
 *   <li>错误信息被简化（如"用户已被封禁"不带 userId），线上排障少一半线索；</li>
 *   <li>想调整顺序或按场景裁剪规则，只能改代码。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】从上往下数 if，注意两个细节：
 * 一是所有规则共用一个方法，无法选择性执行；二是日志只能打在最外层，
 * 分不清是哪条规则失败。这正是不用模式会付出的代价。
 */
@Deprecated(since = "arch-lab", forRemoval = false)
@Service
public class LegacyValidationService {

    private static final Logger log = LoggerFactory.getLogger(LegacyValidationService.class);

    /**
     * 一次性执行全部下单校验。
     *
     * @param context 下单上下文
     * @throws BizException 任一条规则不满足时抛出
     */
    @SuppressWarnings("java:S3776")
    public void validate(OrderContext context) {
        log.debug("【Legacy 校验】开始，userId={}，skuId={}，quantity={}",
                context.userId(), context.skuId(), context.quantity());
        // 规则 1：userId 非空。用户在方法内部被复制了一份，其它入口要用还得再抄一遍
        if (context.userId() == null || context.userId().isBlank()) {
            throw fail("userId 非空", ErrorCode.PARAM_INVALID, "userId 不能为空");
        }
        // 规则 2：封禁判断。错误信息里没有 userId，线上只能去别处反查是谁
        if (context.userId().startsWith("blocked")) {
            throw fail("用户未封禁", ErrorCode.FORBIDDEN, "用户已被封禁");
        }
        // 规则 3：数量区间。魔法值 100 直接写死，阈值一改就要全仓库搜
        if (context.quantity() <= 0 || context.quantity() > 100) {
            throw fail("数量在 (0, 100]", ErrorCode.PARAM_INVALID, "购买数量不合法");
        }
        if (context.skuId() == null || context.skuId().isBlank()) {
            throw fail("skuId 非空", ErrorCode.PARAM_INVALID, "skuId 不能为空");
        }
        if (context.skuId().startsWith("soldout")) {
            throw fail("商品有库存", ErrorCode.CONFLICT, "商品库存不足");
        }
        // Legacy 版只能在这里打一条笼统的"通过"：分不清哪条规则跑过、各用了多久。
        // 这正是对照点——ValidationChain 那边每个节点都有一条 debug 轨迹
        log.debug("【Legacy 校验】全部规则通过（共 5 条，无法分辨单条执行情况）");
    }

    /**
     * 组装异常并补一条失败日志，让 Legacy 侧至少能看出"挂在哪条规则"。
     *
     * <p>这里刻意做成"返回异常"而不是"打日志 + throw"两句：
     * 调用点仍然是一行 {@code throw fail(...)}，保持 if 结构一眼可读。
     *
     * @param rule    失败的是哪条规则
     * @param code    对应的业务错误码
     * @param message 抛给调用方的原因
     * @return 待抛出的异常
     */
    private BizException fail(String rule, ErrorCode code, String message) {
        log.warn("【Legacy 校验】规则「{}」不通过，原因={}", rule, message);
        return new BizException(code, message);
    }
}
