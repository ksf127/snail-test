package com.company.tradejournal.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.company.tradejournal.dto.BatchResult;
import com.company.tradejournal.dto.TradeRequest;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批量录入与 Excel / CSV 导入。
 *
 * <p>【解决什么问题】一笔一笔点表单，导入十笔历史成交要十分钟，而且中途被打断就不知道记到哪了。
 * 但批量导入又不能绕过 {@link TradeService}：持仓成本、已实现盈亏只有一套计算逻辑，
 * 直接拼 INSERT 会让同一标的的成本价出现两个版本，之后所有统计都不可信。
 *
 * <p>【为什么这样设计】逐行调用 {@code tradeService.record}，单行失败只记录行号与原因、不回滚整批。
 * 全有或全无的导入看似安全，实际最难用：一行股票代码写错，整批都进不去，
 * 用户还得自己逐行比对才知道错在哪。
 *
 * <p>【怎么读这段代码】{@link #importFile} 负责识别格式并解析成 {@code TradeRequest}，
 * {@link #batch} 才是真正逐行入库；两者都把错误收集到 {@link BatchResult} 里返回。
 * 模板列顺序见 {@code HEADERS}，改动列顺序时必须同步 {@link #parseRow}。
 */
@Service
public class TradeImportService {

    private static final Logger log = LoggerFactory.getLogger(TradeImportService.class);

    /** 模板列顺序，导入时按位置解析，不按表头文字。 */
    static final List<String> HEADERS = List.of(
            "交易日期", "代码", "名称", "方向", "价格", "数量", "费用", "是否计划内", "理由", "卖出条件");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    private final TradeService tradeService;

    public TradeImportService(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    /**
     * 逐行入库，单行失败不中断。
     *
     * @param requests 已解析的成交
     * @return 成功数、失败数与逐行错误
     */
    public BatchResult batch(List<TradeRequest> requests) {
        List<String> errors = new ArrayList<>();
        int success = 0;
        for (int i = 0; i < requests.size(); i++) {
            TradeRequest request = requests.get(i);
            String invalid = validate(request);
            if (invalid != null) {
                errors.add("第 " + (i + 1) + " 行：" + invalid);
                continue;
            }
            try {
                tradeService.record(request);
                success++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 行：" + e.getMessage());
                log.warn("【批量录入】第 {} 行失败：{}", i + 1, e.getMessage());
            }
        }
        log.info("【批量录入】共 {} 行，成功 {}，失败 {}", requests.size(), success, requests.size() - success);
        return new BatchResult(requests.size(), success, requests.size() - success, errors);
    }

    /**
     * 解析上传文件并入库，支持 .xlsx 与 .csv/.txt。
     *
     * @param file 上传文件
     * @return 导入结果
     * @throws IOException 文件读取失败
     */
    public BatchResult importFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        Parsed parsed = filename.endsWith(".xlsx")
                ? parseExcel(file.getInputStream())
                : parseText(file.getBytes());
        BatchResult result = batch(parsed.requests());
        if (!parsed.errors().isEmpty()) {
            List<String> all = new ArrayList<>(result.errors());
            all.addAll(parsed.errors());
            result = new BatchResult(parsed.total(), result.success(),
                    parsed.total() - result.success(), all);
        }
        log.info("【导入文件】{}｜解析 {} 行，成功 {}，失败 {}", filename, parsed.total(),
                result.success(), result.failed());
        return result;
    }

    /**
     * 生成导入模板（含表头与两行示例）。
     *
     * @return xlsx 字节
     * @throws IOException 写出失败
     */
    public byte[] template() throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("成交导入");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                header.createCell(i).setCellValue(HEADERS.get(i));
                sheet.setColumnWidth(i, 16 * 256);
            }
            String[][] samples = {
                    {"2026-11-05", "600000", "浦发银行", "BUY", "18.50", "500", "5.00", "是", "股息率高于 5%", "股息率跌破 4.5%"},
                    {"2026-11-06", "600000", "浦发银行", "SELL", "19.20", "200", "5.00", "是", "按计划止盈", "计划内止盈"}};
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < samples[r].length; c++) {
                    row.createCell(c).setCellValue(samples[r][c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 解析 xlsx。
     *
     * <p>只支持 .xlsx：.xls 需要额外的 poi-scratchpad，而这个表本身只需要一个简单的录入通道，
     * 让用户另存为 .xlsx 的成本远低于多引一个依赖。
     */
    private Parsed parseExcel(InputStream inputStream) throws IOException {
        List<TradeRequest> requests = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int total = 0;
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String[] cells = new String[HEADERS.size()];
                for (int c = 0; c < HEADERS.size(); c++) {
                    Cell cell = row.getCell(c);
                    cells[c] = cell == null ? "" : readCell(cell, formatter);
                }
                if (isBlankRow(cells) || isHeader(cells)) {
                    continue;
                }
                total++;
                String error = addRow(requests, cells);
                if (error != null) {
                    errors.add("第 " + (r + 1) + " 行（Excel 行号）：" + error);
                }
            }
        }
        return new Parsed(requests, errors, total);
    }

    /**
     * 解析逗号或制表符分隔的文本，允许带 BOM。
     *
     * <p>不处理字段内部的引号与逗号：股票名称与理由里出现逗号会导致错列，
     * 这个场景下让用户改用 Excel 导入比实现半套 CSV 解析更可控。
     */
    private Parsed parseText(byte[] bytes) {
        List<TradeRequest> requests = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        String[] lines = text.split("\\r?\\n");
        char delimiter = lines.length > 0 && lines[0].indexOf('\t') >= 0 ? '\t' : ',';
        int total = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(delimiter == '\t' ? "\\t" : ",", -1);
            if (isHeader(cells)) {
                continue;
            }
            total++;
            String error = addRow(requests, cells);
            if (error != null) {
                errors.add("第 " + (i + 1) + " 行：" + error);
            }
        }
        return new Parsed(requests, errors, total);
    }

    /**
     * 把一行单元格转成成交请求，解析失败时返回原因。
     */
    private String addRow(List<TradeRequest> requests, String[] cells) {
        try {
            requests.add(parseRow(cells));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    private TradeRequest parseRow(String[] cells) {
        if (cells.length < 6) {
            throw new IllegalArgumentException("列数不足，至少需要日期、代码、名称、方向、价格、数量 6 列");
        }
        LocalDate tradeDate = parseDate(cells[0]);
        String symbol = value(cells, 1);
        String name = value(cells, 2);
        String side = parseSide(value(cells, 3));
        BigDecimal price = parseDecimal(value(cells, 4), "价格");
        int quantity = parseQuantity(value(cells, 5));
        BigDecimal fee = value(cells, 6).isEmpty() ? BigDecimal.ZERO : parseDecimal(value(cells, 6), "费用");
        boolean planned = parsePlanned(value(cells, 7));
        String reason = value(cells, 8);
        String sellCondition = value(cells, 9);
        return new TradeRequest(tradeDate, symbol, name, side, price, quantity, fee, planned, null,
                reason, sellCondition, null);
    }

    /**
     * 入库前的必填校验。
     *
     * <p>批量入口不走 {@code @Valid}，注解不会自动生效，所以这里重复一遍最低限度的检查；
     * 更复杂的业务规则（卖出超过持仓等）仍由 {@code TradeService} 负责。
     */
    private String validate(TradeRequest request) {
        if (request == null) {
            return "空行";
        }
        if (request.tradeDate() == null) {
            return "交易日期不能为空";
        }
        if (request.symbol() == null || request.symbol().isBlank()) {
            return "代码不能为空";
        }
        if (request.name() == null || request.name().isBlank()) {
            return "名称不能为空";
        }
        if (request.side() == null || !request.side().matches("BUY|SELL")) {
            return "方向只能是 BUY 或 SELL";
        }
        if (request.price() == null || request.price().signum() <= 0) {
            return "价格必须大于 0";
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            return "数量必须大于 0";
        }
        if (request.fee() != null && request.fee().signum() < 0) {
            return "费用不能为负";
        }
        if (request.reason() == null || request.reason().isBlank()) {
            return "理由不能为空（复盘时要靠它判断决策是否成立）";
        }
        return null;
    }

    private String readCell(Cell cell, DataFormatter formatter) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    private LocalDate parseDate(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("交易日期不能为空");
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 继续试下一种格式
            }
        }
        throw new IllegalArgumentException("交易日期无法识别：" + value + "（支持 yyyy-MM-dd / yyyy/M/d）");
    }

    private String parseSide(String text) {
        String value = text.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "BUY", "B", "买入", "买" -> "BUY";
            case "SELL", "S", "卖出", "卖" -> "SELL";
            default -> throw new IllegalArgumentException("方向无法识别：" + text + "（填 BUY / SELL 或 买入 / 卖出）");
        };
    }

    private BigDecimal parseDecimal(String text, String label) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        try {
            return new BigDecimal(text.replace(",", "").replace("¥", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是数字：" + text);
        }
    }

    private int parseQuantity(String text) {
        BigDecimal value = parseDecimal(text, "数量");
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("数量必须是整数：" + text);
        }
    }

    private boolean parsePlanned(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "是", "y", "yes", "true", "1", "计划内" -> true;
            case "否", "n", "no", "false", "0", "计划外" -> false;
            default -> throw new IllegalArgumentException("是否计划内无法识别：" + text + "（填 是 / 否）");
        };
    }

    private boolean isHeader(String[] cells) {
        String first = cells.length == 0 ? "" : cells[0].trim();
        return first.contains("交易日期") || first.equalsIgnoreCase("tradeDate") || first.equals("日期");
    }

    private boolean isBlankRow(String[] cells) {
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String value(String[] cells, int index) {
        return index < cells.length && cells[index] != null ? cells[index].trim() : "";
    }

    /** 解析结果：可入库的请求、解析阶段就失败的行、参与处理的总行数。 */
    private record Parsed(List<TradeRequest> requests, List<String> errors, int total) {
    }
}
