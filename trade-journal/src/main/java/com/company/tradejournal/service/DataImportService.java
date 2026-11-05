package com.company.tradejournal.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.tradejournal.dto.TradeRequest;
import com.company.tradejournal.dto.imports.ImportColumn;
import com.company.tradejournal.dto.imports.ImportCommitRequest;
import com.company.tradejournal.dto.imports.ImportCommitResult;
import com.company.tradejournal.dto.imports.ImportPreview;
import com.company.tradejournal.dto.imports.ImportRow;
import com.company.tradejournal.entity.AccountDailyEntity;
import com.company.tradejournal.entity.PositionDailyEntity;
import com.company.tradejournal.entity.PositionEntity;
import com.company.tradejournal.entity.TradeEntity;
import com.company.tradejournal.mapper.AccountDailyMapper;
import com.company.tradejournal.mapper.PositionDailyMapper;
import com.company.tradejournal.mapper.PositionMapper;
import com.company.tradejournal.mapper.TradeMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 一键导入：券商的成交流水、每日资金、当前持仓直接落库。
 *
 * <p>【解决什么问题】手工一笔一笔录历史成交既慢又容易错，而错一笔价格，
 * 后面的胜率、盈亏比、净值全跟着偏。这里把「读文件 → 认列 → 校验 → 查重 → 入库」做成一条链路，
 * 导入前先给预览，用户确认后才写库。
 *
 * <p>【为什么成交仍走 TradeService】持仓成本与已实现盈亏只有一套计算逻辑。
 * 直接拼 INSERT 会让同一标的出现两个成本价版本，之后所有统计都不可信。
 * 因此成交逐行调用 {@link TradeService#record}，单行失败只记错误、不回滚整批。
 *
 * <p>【三种数据的边界】成交流水会维护持仓与已实现盈亏；每日资金只写净值表；
 * 当前持仓只覆盖 {@code t_position}（有最新价时同时写当天快照），不反推成交。
 * 想统计卖出胜率，必须导成交流水，单导持仓是算不出来的。
 */
@Service
public class DataImportService {

    private static final Logger log = LoggerFactory.getLogger(DataImportService.class);

    /** 未指定账户时与手工录入保持一致，落到默认账户。 */
    private static final long DEFAULT_ACCOUNT_ID = 1L;

    private static final String DEFAULT_REASON = "批量导入";

    private static final int RATIO_SCALE = 4;

    private final ImportParser parser;

    private final TradeService tradeService;

    private final TradeMapper tradeMapper;

    private final AccountDailyMapper accountDailyMapper;

    private final PositionMapper positionMapper;

    private final PositionDailyMapper positionDailyMapper;

    public DataImportService(ImportParser parser, TradeService tradeService, TradeMapper tradeMapper,
                             AccountDailyMapper accountDailyMapper, PositionMapper positionMapper,
                             PositionDailyMapper positionDailyMapper) {
        this.parser = parser;
        this.tradeService = tradeService;
        this.tradeMapper = tradeMapper;
        this.accountDailyMapper = accountDailyMapper;
        this.positionMapper = positionMapper;
        this.positionDailyMapper = positionDailyMapper;
    }

    /**
     * 解析上传文件并做查重，不写库。
     *
     * @param type      导入类型
     * @param accountId 目标账户
     * @param file      上传文件
     * @return 预览结果
     * @throws IOException 文件读取失败
     */
    public ImportPreview preview(String type, Long accountId, MultipartFile file) throws IOException {
        requireSupported(type);
        ImportParser.Table table = parser.read(file);
        return buildPreview(type, accountId, table.rows(), file.getOriginalFilename(), table.warnings());
    }

    /**
     * 解析磁盘文件的字节内容并做查重，不写库。定时同步扫描下载目录时使用。
     *
     * @param type     导入类型
     * @param accountId 目标账户
     * @param bytes    文件内容
     * @param fileName 文件名
     * @return 预览结果
     * @throws IOException 文件读取失败
     */
    public ImportPreview preview(String type, Long accountId, byte[] bytes, String fileName) throws IOException {
        requireSupported(type);
        ImportParser.Table table = parser.read(bytes, fileName);
        return buildPreview(type, accountId, table.rows(), fileName, table.warnings());
    }

    /**
     * 解析粘贴的文本并做查重，不写库。
     *
     * @param type      导入类型
     * @param accountId 目标账户
     * @param text      粘贴的表格文本
     * @return 预览结果
     */
    public ImportPreview previewPaste(String type, Long accountId, String text) {
        requireSupported(type);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("粘贴内容为空");
        }
        ImportParser.Table table = parser.readPaste(text);
        return buildPreview(type, accountId, table.rows(), "粘贴内容", table.warnings());
    }

    private void requireSupported(String type) {
        if (!parser.supports(type)) {
            throw new IllegalArgumentException("不支持的导入类型：" + type);
        }
    }

    private ImportPreview buildPreview(String type, Long accountId, List<String[]> rows,
                                       String fileName, List<String> warnings) {
        long acct = accountId == null ? DEFAULT_ACCOUNT_ID : accountId;
        ImportParser.Mapping mapping = parser.map(type, rows);
        List<String> messages = new ArrayList<>(warnings);
        if (mapping.headerRow() < 0) {
            messages.add("没有识别到表头，已按模板的固定列顺序解析；建议保留表头后再导入");
        }
        if (!mapping.missing().isEmpty()) {
            messages.add("缺少必填列：" + String.join("、", mapping.missing()));
        }
        if (ImportParser.TYPE_DAILY.equals(type) && !mapping.indexes().containsKey("cash")) {
            messages.add("没有识别到可用资金列，现金按 0 处理；资金曲线会偏大");
        }
        if (ImportParser.TYPE_POSITIONS.equals(type) && !mapping.indexes().containsKey("avgCost")) {
            messages.add("没有识别到成本价列，将用最新价替代成本");
        }

        Set<LocalDate> existingDays = new HashSet<>();
        Map<String, Integer> existingTrades = Map.of();
        Map<String, PositionEntity> holdings = Map.of();
        if (ImportParser.TYPE_DAILY.equals(type)) {
            existingDays.addAll(accountDailyMapper.selectDates(acct, LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)));
        } else if (ImportParser.TYPE_POSITIONS.equals(type)) {
            holdings = new HashMap<>();
            for (PositionEntity position : positionMapper.selectHoldings(acct)) {
                holdings.put(position.getSymbol(), position);
            }
        }

        List<ImportRow> result = new ArrayList<>();
        int dataStart = mapping.headerRow() + 1;
        LocalDate minDate = null;
        LocalDate maxDate = null;
        List<ImportParser.ParsedRow> parsedRows = new ArrayList<>();
        for (int r = dataStart; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            if (isBlank(cells)) {
                continue;
            }
            ImportParser.ParsedRow parsed = parser.normalize(type, cells, mapping, r + 1);
            parsedRows.add(parsed);
            String date = parsed.data().get("tradeDate");
            if (date != null) {
                LocalDate day = LocalDate.parse(date);
                minDate = minDate == null || day.isBefore(minDate) ? day : minDate;
                maxDate = maxDate == null || day.isAfter(maxDate) ? day : maxDate;
            }
        }
        if (ImportParser.TYPE_TRADES.equals(type) && minDate != null) {
            existingTrades = tradeKeyCounts(tradeMapper.selectForDedup(acct, minDate, maxDate));
        }

        int valid = 0;
        int duplicate = 0;
        int invalid = 0;
        for (ImportParser.ParsedRow parsed : parsedRows) {
            String status = "VALID";
            String message = "";
            if (parsed.error() != null) {
                status = "INVALID";
                message = parsed.error();
                invalid++;
            } else if (ImportParser.TYPE_TRADES.equals(type) && consume(existingTrades, tradeKey(parsed.data()))) {
                status = "DUPLICATE";
                message = "数据库里已有这笔成交，默认跳过";
                duplicate++;
            } else if (ImportParser.TYPE_DAILY.equals(type)
                    && existingDays.contains(LocalDate.parse(parsed.data().get("tradeDate")))) {
                status = "DUPLICATE";
                message = "该日期已有净值，导入后覆盖";
                duplicate++;
            } else if (ImportParser.TYPE_POSITIONS.equals(type)
                    && holdings.containsKey(parsed.data().get("symbol"))) {
                status = "DUPLICATE";
                message = "已有持仓，导入后覆盖数量与成本";
                duplicate++;
            } else {
                valid++;
            }
            result.add(new ImportRow(parsed.rowNo(), status, message, parsed.data()));
        }
        if (result.isEmpty()) {
            messages.add("文件里没有解析到数据行，请检查是否选错了导入类型");
        }
        List<ImportColumn> columns = mapping.columns().stream()
                .filter(c -> !"amount".equals(c.field()))
                .toList();
        log.info("【导入预览】类型 {}｜账户 {}｜{} 行，可导入 {}，重复 {}，异常 {}",
                type, acct, result.size(), valid, duplicate, invalid);
        return new ImportPreview(type, fileName == null ? "" : fileName, result.size(), valid, duplicate, invalid,
                columns, mapping.missing(), messages, result);
    }

    private boolean isBlank(String[] cells) {
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把预览确认过的行写入数据库。
     *
     * <p>【为什么再校验一遍】预览到提交之间用户可能改了别的数据，
     * 提交只相信「行的内容」，不接受「预览说没问题」这个结论。
     *
     * @param request 提交内容
     * @return 成功、跳过、失败统计
     */
    public ImportCommitResult commit(ImportCommitRequest request) {
        String type = request.type();
        requireSupported(type);
        long accountId = request.accountId() == null ? DEFAULT_ACCOUNT_ID : request.accountId();
        List<Map<String, String>> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("没有可导入的数据行");
        }
        String defaultReason = request.defaultReason() == null || request.defaultReason().isBlank()
                ? DEFAULT_REASON : request.defaultReason().trim();
        boolean skipDuplicates = request.skipDuplicates() == null || request.skipDuplicates();
        ImportCommitResult result;
        if (ImportParser.TYPE_TRADES.equals(type)) {
            result = commitTrades(accountId, rows, defaultReason, skipDuplicates);
        } else if (ImportParser.TYPE_DAILY.equals(type)) {
            result = commitDaily(accountId, rows);
        } else {
            result = commitPositions(accountId, rows);
        }
        log.info("【导入提交】类型 {}｜账户 {}｜共 {} 行，成功 {}，跳过 {}，失败 {}", type, accountId,
                result.total(), result.success(), result.skipped(), result.failed());
        return result;
    }

    private ImportCommitResult commitTrades(long accountId, List<Map<String, String>> rows,
                                            String defaultReason, boolean skipDuplicates) {
        List<Map<String, String>> ordered = new ArrayList<>(rows);
        // 按日期升序，保证同一天先买后卖也能通过持仓校验
        ordered.sort(Comparator.comparing(row -> value(row, "tradeDate")));

        LocalDate min = null;
        LocalDate max = null;
        for (Map<String, String> row : ordered) {
            LocalDate date = parser.toDate(value(row, "tradeDate"));
            if (date != null) {
                min = min == null || date.isBefore(min) ? date : min;
                max = max == null || date.isAfter(max) ? date : max;
            }
        }
        Map<String, Integer> existing = min == null
                ? new HashMap<>()
                : tradeKeyCounts(tradeMapper.selectForDedup(accountId, min, max));

        List<String> errors = new ArrayList<>();
        int success = 0;
        int skipped = 0;
        for (Map<String, String> row : ordered) {
            String rowLabel = rowLabel(row);
            String error = validateTradeRow(row);
            if (error != null) {
                errors.add(rowLabel + "：" + error);
                continue;
            }
            String key = tradeKey(row);
            if (skipDuplicates && consume(existing, key)) {
                skipped++;
                continue;
            }
            TradeRequest request = new TradeRequest(
                    parser.toDate(value(row, "tradeDate")),
                    value(row, "symbol"),
                    value(row, "name").isEmpty() ? value(row, "symbol") : value(row, "name"),
                    value(row, "side"),
                    parser.toNumber(value(row, "price")),
                    parser.toNumber(value(row, "quantity")).intValue(),
                    parser.toNumber(value(row, "fee")) == null ? BigDecimal.ZERO : parser.toNumber(value(row, "fee")),
                    true,
                    null,
                    value(row, "reason").isEmpty() ? defaultReason : value(row, "reason"),
                    value(row, "sellCondition"),
                    accountId);
            try {
                tradeService.record(request);
                success++;
            } catch (Exception e) {
                errors.add(rowLabel + "：" + e.getMessage());
            }
        }
        return new ImportCommitResult(ImportParser.TYPE_TRADES, ordered.size(), success, skipped,
                ordered.size() - success - skipped, errors);
    }

    private ImportCommitResult commitDaily(long accountId, List<Map<String, String>> rows) {
        List<String> errors = new ArrayList<>();
        int success = 0;
        for (Map<String, String> row : rows) {
            String rowLabel = rowLabel(row);
            LocalDate date = parser.toDate(value(row, "tradeDate"));
            BigDecimal equity = parser.toNumber(value(row, "equity"));
            if (date == null) {
                errors.add(rowLabel + "：日期无法识别");
                continue;
            }
            if (equity == null || equity.signum() < 0) {
                errors.add(rowLabel + "：总资产缺失或为负数");
                continue;
            }
            BigDecimal cash = parser.toNumber(value(row, "cash"));
            AccountDailyEntity entity = new AccountDailyEntity();
            entity.setAccountId(accountId);
            entity.setTradeDate(date);
            entity.setEquity(equity);
            entity.setCash(cash == null ? BigDecimal.ZERO : cash);
            entity.setNote(value(row, "note"));
            try {
                accountDailyMapper.upsert(entity);
                success++;
            } catch (Exception e) {
                errors.add(rowLabel + "：" + e.getMessage());
            }
        }
        return new ImportCommitResult(ImportParser.TYPE_DAILY, rows.size(), success, 0,
                rows.size() - success, errors);
    }

    private ImportCommitResult commitPositions(long accountId, List<Map<String, String>> rows) {
        List<String> errors = new ArrayList<>();
        int success = 0;
        for (Map<String, String> row : rows) {
            String rowLabel = rowLabel(row);
            String symbol = value(row, "symbol");
            BigDecimal quantityValue = parser.toNumber(value(row, "quantity"));
            BigDecimal avgCost = parser.toNumber(value(row, "avgCost"));
            if (symbol.isEmpty()) {
                errors.add(rowLabel + "：证券代码为空");
                continue;
            }
            if (quantityValue == null || quantityValue.signum() <= 0) {
                errors.add(rowLabel + "：持仓数量必须大于 0");
                continue;
            }
            if (avgCost == null || avgCost.signum() <= 0) {
                errors.add(rowLabel + "：成本价必须大于 0");
                continue;
            }
            int quantity = quantityValue.intValue();
            String name = value(row, "name").isEmpty() ? symbol : value(row, "name");
            try {
                PositionEntity position = positionMapper.selectPosition(accountId, symbol);
                if (position == null) {
                    position = new PositionEntity();
                    position.setAccountId(accountId);
                    position.setSymbol(symbol);
                    position.setName(name);
                    position.setQuantity(quantity);
                    position.setAvgCost(avgCost);
                    positionMapper.insertPosition(position);
                } else {
                    position.setName(name);
                    position.setQuantity(quantity);
                    position.setAvgCost(avgCost);
                    positionMapper.updatePosition(position);
                }
                writeSnapshot(accountId, row, symbol, name, quantity, avgCost);
                success++;
            } catch (Exception e) {
                errors.add(rowLabel + "：" + e.getMessage());
            }
        }
        return new ImportCommitResult(ImportParser.TYPE_POSITIONS, rows.size(), success, 0,
                rows.size() - success, errors);
    }

    /** 有最新价时同时写当天快照，仓位占比用最近一天的账户净值算。 */
    private void writeSnapshot(long accountId, Map<String, String> row, String symbol, String name,
                               int quantity, BigDecimal avgCost) {
        BigDecimal closePrice = parser.toNumber(value(row, "closePrice"));
        if (closePrice == null || closePrice.signum() <= 0) {
            return;
        }
        LocalDate date = parser.toDate(value(row, "snapshotDate"));
        if (date == null) {
            date = LocalDate.now();
        }
        BigDecimal equity = null;
        AccountDailyEntity daily = accountDailyMapper.selectDaily(accountId, date);
        if (daily == null) {
            daily = accountDailyMapper.selectLatest(accountId);
        }
        if (daily != null) {
            equity = daily.getEquity();
        }
        BigDecimal ratio = equity == null || equity.signum() <= 0
                ? BigDecimal.ZERO
                : closePrice.multiply(BigDecimal.valueOf(quantity)).divide(equity, RATIO_SCALE, RoundingMode.HALF_UP);
        PositionDailyEntity snapshot = new PositionDailyEntity();
        snapshot.setAccountId(accountId);
        snapshot.setSnapshotDate(date);
        snapshot.setSymbol(symbol);
        snapshot.setName(name);
        snapshot.setQuantity(quantity);
        snapshot.setAvgCost(avgCost);
        snapshot.setClosePrice(closePrice);
        snapshot.setPositionRatio(ratio);
        snapshot.setLogicValid(true);
        positionDailyMapper.upsert(snapshot);
    }

    private String validateTradeRow(Map<String, String> row) {
        if (parser.toDate(value(row, "tradeDate")) == null) {
            return "交易日期无法识别";
        }
        if (value(row, "symbol").isEmpty()) {
            return "证券代码为空";
        }
        String side = value(row, "side");
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            return "买卖方向只能是 BUY 或 SELL";
        }
        BigDecimal price = parser.toNumber(value(row, "price"));
        if (price == null || price.signum() <= 0) {
            return "成交价格必须大于 0";
        }
        BigDecimal quantity = parser.toNumber(value(row, "quantity"));
        if (quantity == null || quantity.signum() <= 0) {
            return "成交数量必须大于 0";
        }
        return null;
    }

    private String rowLabel(Map<String, String> row) {
        String rowNo = value(row, "_rowNo");
        return rowNo.isEmpty() ? "提交行" : "第 " + rowNo + " 行";
    }

    private String value(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }

    /** 成交查重键：日期 + 代码 + 方向 + 价格 + 数量，五项都相同视为同一笔。 */
    private String tradeKey(Map<String, String> row) {
        BigDecimal price = parser.toNumber(value(row, "price"));
        BigDecimal quantity = parser.toNumber(value(row, "quantity"));
        return value(row, "tradeDate") + "|" + value(row, "symbol") + "|" + value(row, "side") + "|"
                + (price == null ? "" : price.stripTrailingZeros().toPlainString()) + "|"
                + (quantity == null ? "" : String.valueOf(quantity.intValue()));
    }

    private Map<String, Integer> tradeKeyCounts(List<TradeEntity> trades) {
        Map<String, Integer> counts = new HashMap<>();
        for (TradeEntity trade : trades) {
            String key = trade.getTradeDate() + "|" + trade.getSymbol() + "|" + trade.getSide() + "|"
                    + (trade.getPrice() == null ? "" : trade.getPrice().stripTrailingZeros().toPlainString()) + "|"
                    + trade.getQuantity();
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /** 数据库里同键的笔数用完之前都算「新成交」，避免把同价同量的两笔真实成交误判为重复。 */
    private boolean consume(Map<String, Integer> counts, String key) {
        Integer remaining = counts.get(key);
        if (remaining == null || remaining <= 0) {
            return false;
        }
        counts.put(key, remaining - 1);
        return true;
    }
}
