package com.company.service.archlab.pattern.validation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 校验链：Spring 会按 {@code @Order} 把全部 {@link OrderValidator} 注入成有序 List，
 * 这里只负责按顺序执行 + 记录执行轨迹。
 *
 * <p>为什么用 List 注入而不是自己 new：
 * 节点由容器管理，需要数据库/缓存/配置时直接注入依赖；
 * 新增校验只要加一个 {@code @Component}，链路自动生效。
 */
@Service
public class ValidationChain {

    private final List<OrderValidator> validators;

    public ValidationChain(List<OrderValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    /**
     * @return 实际执行过的节点名（按执行顺序），用于回显与排障
     */
    public List<String> validate(OrderContext context) {
        List<String> executed = new ArrayList<>(validators.size());
        for (OrderValidator validator : validators) {
            validator.validate(context);
            executed.add(validator.name());
        }
        return executed;
    }

    public List<String> chain() {
        return validators.stream().map(OrderValidator::name).toList();
    }
}
