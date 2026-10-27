package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.stereotype.Service;

/**
 * 反面教材：把三条规则塞进一个方法。
 *
 * <p>它的演进终点很固定：
 * 今天加"限购"，明天加"黑名单"，后天加"活动资格"，
 * 最后这个方法 300 行、十几个 if、每个 if 里还有嵌套，没人敢动，也没法单独测。
 */
@Deprecated(since = "arch-lab", forRemoval = false)
@Service
public class LegacyValidationService {

    @SuppressWarnings("java:S3776")
    public void validate(OrderContext context) {
        if (context.userId() == null || context.userId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "userId 不能为空");
        }
        if (context.userId().startsWith("blocked")) {
            throw new BizException(ErrorCode.FORBIDDEN, "用户已被封禁");
        }
        if (context.quantity() <= 0 || context.quantity() > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID, "购买数量不合法");
        }
        if (context.skuId() == null || context.skuId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "skuId 不能为空");
        }
        if (context.skuId().startsWith("soldout")) {
            throw new BizException(ErrorCode.CONFLICT, "商品库存不足");
        }
    }
}
