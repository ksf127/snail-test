package com.company.tradejournal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.tradejournal.dto.account.AccountEquitySeries;
import com.company.tradejournal.dto.account.AccountRequest;
import com.company.tradejournal.dto.account.AccountSummary;
import com.company.tradejournal.dto.account.AccountTradeAggRow;
import com.company.tradejournal.dto.stats.EquityPoint;
import com.company.tradejournal.entity.AccountDailyEntity;
import com.company.tradejournal.entity.AccountEntity;
import com.company.tradejournal.mapper.AccountDailyMapper;
import com.company.tradejournal.mapper.AccountMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户：新建、列表汇总、净值序列。
 *
 * <p>【解决什么问题】账户不只是一个名字：页面上「当前余额、可用现金、总盈亏、收益率」
 * 这四个数要实时给出，而它们分别来自每日净值表、成交表和持仓表。
 * 如果没有一个统一的汇总出口，每个页面都会各写一套口径，最后「账户页」和「统计页」对不上。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>汇总以「最新一条每日净值」为真：净值是复盘时确认过的结果，
 *       用持仓现价重算会和历史对不上，也会把行情波动混进已确认的账。</li>
 *   <li>浮动盈亏单独估算，只用于「现在大概赚了多少」，不写入净值表。</li>
 *   <li>新建账户时若填了初始资金，立刻写一条今天的净值：
 *       否则新账户在页面上会显示余额 0，用户会以为数据没保存。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】{@link #summary} 是唯一的汇总计算入口，
 * 列表和新建都复用它，保证两条路径的口径绝对一致。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final int AMOUNT_SCALE = 2;

    private static final int RATIO_SCALE = 4;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final String DEFAULT_CURRENCY = "CNY";

    private static final String OPENING_NOTE = "开户初始资金";

    private final AccountMapper accountMapper;

    private final AccountDailyMapper accountDailyMapper;

    public AccountService(AccountMapper accountMapper, AccountDailyMapper accountDailyMapper) {
        this.accountMapper = accountMapper;
        this.accountDailyMapper = accountDailyMapper;
    }

    /**
     * 启用中的账户汇总，按 id 升序。
     *
     * <p>只返回启用账户：停用账户的历史数据仍参与「全部账户」统计，
     * 但不再出现在下拉框里，避免误选一个已经销户的账户下单。
     *
     * @return 账户汇总列表
     */
    public List<AccountSummary> list() {
        List<AccountSummary> result = accountMapper.selectActive().stream()
                .map(this::summary)
                .toList();
        log.info("【账户·列表】启用账户 {} 个：{}", result.size(),
                result.stream().map(item -> item.id() + "-" + item.name()
                        + " 净值 " + item.equity()).toList());
        return result;
    }

    /**
     * 新建账户。
     *
     * <p>用事务包住「建账户 + 写初始净值」：两步必须一起成功，
     * 否则会出现一个余额为 0、收益率算不出来的账户。
     *
     * @param request 新建参数
     * @return 新账户的汇总；填了初始资金时余额立即可见
     * @throws BizException 名称为空时抛出
     */
    @Transactional
    public AccountSummary create(AccountRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "账户名称不能为空");
        }
        BigDecimal initialCapital = money(request.initialCapital());

        AccountEntity account = new AccountEntity();
        account.setName(name);
        account.setBroker(trimToNull(request.broker()));
        account.setCurrency(normalizeCurrency(request.currency()));
        account.setInitialCapital(initialCapital);
        account.setNote(trimToNull(request.note()));
        account.setActive(true);
        accountMapper.insert(account);

        // 初始资金当天入账：余额和收益率从创建那一刻起就有基准，不用等到晚上复盘
        if (initialCapital.signum() > 0) {
            AccountDailyEntity opening = new AccountDailyEntity();
            opening.setAccountId(account.getId());
            opening.setTradeDate(LocalDate.now());
            opening.setEquity(initialCapital);
            opening.setCash(initialCapital);
            opening.setNote(OPENING_NOTE);
            accountDailyMapper.upsert(opening);
        }

        log.info("【账户·新建】ID={}｜{}｜{}｜初始资金 {}｜开户净值{}",
                account.getId(), account.getName(), account.getCurrency(), initialCapital,
                initialCapital.signum() > 0 ? "已写入 " + LocalDate.now() : "未写入（初始资金为 0）");
        return summary(account);
    }

    /**
     * 每个启用账户一条净值序列。
     *
     * <p>没有数据的账户也返回（points 为空数组），前端不用再比对账户列表和返回结果，
     * 直接按返回顺序画就能保持图例稳定。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 按账户 id 升序
     */
    public List<AccountEquitySeries> equitySeries(LocalDate from, LocalDate to) {
        List<AccountEntity> accounts = accountMapper.selectActive();
        List<AccountEquitySeries> result = new ArrayList<>(accounts.size());
        for (AccountEntity account : accounts) {
            List<EquityPoint> points = accountDailyMapper.selectSeries(account.getId(), from, to);
            result.add(new AccountEquitySeries(account.getId(), account.getName(), points));
        }
        log.info("【账户·净值】{} ~ {}｜{} 个账户，点数 {}",
                from, to, result.size(),
                result.stream().mapToInt(item -> item.points().size()).sum());
        return result;
    }

    /**
     * 单个账户的汇总口径。
     *
     * <p>金额统一两位小数、null 归零；胜率没有卖出记录时返回 null，
     * 因为「0% 胜率」和「还没卖过」在页面上是两种完全不同的结论。
     *
     * @param account 账户
     * @return 汇总
     */
    private AccountSummary summary(AccountEntity account) {
        AccountDailyEntity latest = accountDailyMapper.selectLatest(account.getId());
        BigDecimal equity = latest == null ? money(BigDecimal.ZERO) : money(latest.getEquity());
        BigDecimal cash = latest == null ? money(BigDecimal.ZERO) : money(latest.getCash());
        BigDecimal marketValue = money(equity.subtract(cash));

        AccountTradeAggRow agg = accountMapper.selectTradeAgg(account.getId());
        BigDecimal realizedPnl = agg == null ? money(BigDecimal.ZERO) : money(agg.getRealizedPnl());
        BigDecimal profitAmount = agg == null ? money(BigDecimal.ZERO) : money(agg.getProfitAmount());
        BigDecimal lossAmount = agg == null ? money(BigDecimal.ZERO)
                : money(agg.getLossAmount() == null ? BigDecimal.ZERO : agg.getLossAmount().abs());
        int tradeCount = agg == null || agg.getTradeCount() == null ? 0 : agg.getTradeCount();
        int sellCount = agg == null || agg.getSellCount() == null ? 0 : agg.getSellCount();
        int winCount = agg == null || agg.getWinCount() == null ? 0 : agg.getWinCount();

        BigDecimal floatingPnl = money(accountMapper.selectFloatingPnl(account.getId()));
        BigDecimal totalPnl = money(realizedPnl.add(floatingPnl));

        BigDecimal initialCapital = money(account.getInitialCapital());
        BigDecimal returnPct = initialCapital.signum() == 0 ? null
                : equity.subtract(initialCapital).multiply(HUNDRED)
                .divide(initialCapital, AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal winRate = sellCount == 0 ? null
                : BigDecimal.valueOf(winCount)
                .divide(BigDecimal.valueOf(sellCount), RATIO_SCALE, RoundingMode.HALF_UP);

        return new AccountSummary(
                account.getId(),
                account.getName(),
                account.getBroker(),
                account.getCurrency(),
                initialCapital,
                equity,
                cash,
                marketValue,
                realizedPnl,
                floatingPnl,
                totalPnl,
                returnPct,
                winRate,
                profitAmount,
                lossAmount,
                tradeCount,
                accountMapper.selectPositionCount(account.getId()),
                latest == null ? null : latest.getTradeDate());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                : value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeCurrency(String currency) {
        String value = currency == null ? "" : currency.trim().toUpperCase();
        return value.isEmpty() ? DEFAULT_CURRENCY : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
