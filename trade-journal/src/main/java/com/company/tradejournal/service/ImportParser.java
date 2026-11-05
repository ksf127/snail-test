package com.company.tradejournal.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.company.tradejournal.dto.imports.ImportColumn;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 把券商导出的表格读成「一行行归一化数据」。
 *
 * <p>【解决什么问题】不同券商的导出列名、顺序、编码都不一样：有的叫「成交日期」，有的叫「发生日期」，
 * 有的用 GBK，有的用制表符分隔。如果按固定列位置解析，用户每换一家券商就得改一次代码。
 *
 * <p>【怎么认列】按中文表头关键字打分（完全相同 &gt; 包含 &gt; 被包含），每个字段取分数最高的列；
 * 手续费类列（佣金、印花税、过户费）会被<b>相加</b>成一列费用。
 * 找不到表头时退化为固定列顺序，并在预览里提示。
 *
 * <p>【为什么归一化后再入库】预览和提交共用同一份归一化结果，用户看到的「价格 18.50」就是最终入库的值，
 * 不会出现预览正常、入库解析失败的情况。
 */
@Component
public class ImportParser {

    public static final String TYPE_TRADES = "trades";

    public static final String TYPE_DAILY = "daily";

    public static final String TYPE_POSITIONS = "positions";

    /** 一次最多解析的行数，避免拖一个几十万行的文件把页面卡死。 */
    static final int MAX_ROWS = 2000;

    /** 字段定义：内部名、中文名、表头关键字、是否必填、需要排除的表头词。 */
    record FieldSpec(String field, String label, List<String> keywords, boolean required, List<String> blocked) {
    }

    /** 一个文件的原始表格。 */
    record Table(List<String[]> rows, List<String> warnings) {
    }

    /** 列识别结果。{@code header} 用于识别方向等非常规列，固定列顺序时为 null。 */
    record Mapping(Map<String, List<Integer>> indexes, List<ImportColumn> columns,
                   List<String> missing, int headerRow, String[] header) {
    }

    /** 一行归一化结果，{@code error} 非空表示这行不能导入。 */
    record ParsedRow(int rowNo, Map<String, String> data, String error) {
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"));

    /** 各导入类型的字段定义，顺序即预览表列顺序。 */
    private static final Map<String, List<FieldSpec>> FIELDS = buildFields();

    private static Map<String, List<FieldSpec>> buildFields() {
        Map<String, List<FieldSpec>> map = new LinkedHashMap<>();
        map.put(TYPE_TRADES, List.of(
                new FieldSpec("tradeDate", "交易日期",
                        List.of("成交日期", "交易日期", "发生日期", "成交时间", "委托日期", "日期"),
                        true, List.of("市值", "盈亏", "收益")),
                new FieldSpec("symbol", "证券代码",
                        List.of("证券代码", "股票代码", "证券编码", "股票编码", "代码"),
                        true, List.of("市场", "名称")),
                new FieldSpec("name", "证券名称",
                        List.of("证券名称", "股票名称", "证券简称", "名称"),
                        false, List.of("代码")),
                new FieldSpec("side", "买卖方向",
                        List.of("买卖标志", "买卖方向", "委托方向", "操作", "业务名称", "交易类型", "方向"),
                        true, List.of("日期", "数量", "价格")),
                new FieldSpec("price", "成交价格",
                        List.of("成交均价", "成交价格", "成交价", "委托价格", "价格"),
                        false, List.of("市值", "盈亏", "金额", "数量")),
                new FieldSpec("quantity", "成交数量",
                        List.of("成交数量", "成交量", "成交股数", "股数", "数量"),
                        true, List.of("市值", "盈亏", "金额", "占比", "比例", "可用")),
                new FieldSpec("fee", "费用合计",
                        List.of("手续费", "佣金", "印花税", "过户费", "规费", "交易费用", "费用"),
                        false, List.of()),
                new FieldSpec("amount", "成交金额",
                        List.of("成交金额", "成交额", "发生金额"),
                        false, List.of("市值", "盈亏")),
                new FieldSpec("reason", "理由",
                        List.of("买入理由", "交易理由", "备注", "摘要"),
                        false, List.of("代码", "名称")),
                new FieldSpec("sellCondition", "卖出条件", List.of("卖出条件"), false, List.of())));

        map.put(TYPE_DAILY, List.of(
                new FieldSpec("tradeDate", "日期",
                        List.of("净值日期", "对账日期", "交易日期", "日期", "时间"),
                        true, List.of("市值", "盈亏")),
                new FieldSpec("equity", "总资产",
                        List.of("总资产", "资产总值", "资产合计", "总权益", "净资产", "账户资产"),
                        true, List.of("可用", "现金", "盈亏", "市值")),
                new FieldSpec("cash", "可用资金",
                        List.of("可用资金", "可用金额", "资金余额", "可用余额", "可取资金", "可用", "现金"),
                        false, List.of("市值", "盈亏", "资产")),
                new FieldSpec("note", "备注",
                        List.of("备注", "说明", "摘要"),
                        false, List.of())));

        map.put(TYPE_POSITIONS, List.of(
                new FieldSpec("snapshotDate", "快照日期",
                        List.of("快照日期", "持仓日期", "对账日期", "日期"),
                        false, List.of("市值", "盈亏")),
                new FieldSpec("symbol", "证券代码",
                        List.of("证券代码", "股票代码", "证券编码", "股票编码", "代码"),
                        true, List.of("市场", "名称")),
                new FieldSpec("name", "证券名称",
                        List.of("证券名称", "股票名称", "证券简称", "名称"),
                        false, List.of("代码")),
                new FieldSpec("quantity", "持仓数量",
                        List.of("当前持仓", "持仓数量", "股票余额", "股份余额", "持仓股数", "数量", "持仓"),
                        true, List.of("市值", "盈亏", "金额", "占比", "比例", "可用", "冻结")),
                new FieldSpec("avgCost", "成本价",
                        List.of("摊薄成本价", "参考成本价", "持仓成本价", "成本价", "平均成本", "成本"),
                        false, List.of("市值", "盈亏", "金额", "比例")),
                new FieldSpec("closePrice", "最新价",
                        List.of("最新价", "最新价格", "现价", "市价", "收盘价"),
                        false, List.of("成本", "盈亏", "市值"))));
        return map;
    }

    /**
     * 读上传文件，按扩展名与文件头判断 xlsx 还是文本。
     *
     * @param file 上传文件
     * @return 原始表格
     * @throws IOException 读取失败
     */
    public Table read(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        return read(file.getBytes(), name);
    }

    /**
     * 读磁盘上的文件内容，供定时同步扫描下载目录使用。
     *
     * @param bytes    文件字节
     * @param fileName 文件名，用于判断扩展名
     * @return 原始表格
     * @throws IOException 读取失败
     */
    public Table read(byte[] bytes, String fileName) throws IOException {
        String name = fileName == null ? "" : fileName;
        boolean excel = name.toLowerCase(Locale.ROOT).endsWith(".xlsx") || isZip(bytes);
        if (excel) {
            List<String> warnings = new ArrayList<>();
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                List<String[]> rows = readExcel(in, warnings);
                return new Table(rows, warnings);
            }
        }
        String text = decode(bytes);
        if (text.indexOf('\uFFFD') >= 0) {
            text = new String(bytes, Charset.forName("GBK"));
        }
        List<String> warnings = new ArrayList<>();
        List<String[]> rows = parseCsv(text, detectDelimiter(text));
        return new Table(rows, warnings);
    }

    /**
     * 解析粘贴进来的文本。
     *
     * @param text 用户粘贴的表格
     * @return 原始表格
     */
    public Table readPaste(String text) {
        List<String> warnings = new ArrayList<>();
        List<String[]> rows = parseCsv(text, detectDelimiter(text));
        return new Table(rows, warnings);
    }

    private boolean isZip(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0x50 && (bytes[1] & 0xFF) == 0x4B;
    }

    /** UTF-8 严格解码，失败则按 GBK；Excel 导出的中文 CSV 多数是 GBK。 */
    private String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /** 在前 5 行里数分隔符，取出现最多的那个。 */
    private char detectDelimiter(String text) {
        String head = text.length() > 4000 ? text.substring(0, 4000) : text;
        int comma = 0;
        int tab = 0;
        int semi = 0;
        for (String line : head.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            for (char ch : line.toCharArray()) {
                if (ch == ',') {
                    comma++;
                } else if (ch == '\t') {
                    tab++;
                } else if (ch == ';') {
                    semi++;
                }
            }
            if (comma + tab + semi > 0) {
                break;
            }
        }
        if (tab >= comma && tab >= semi && tab > 0) {
            return '\t';
        }
        return semi > comma ? ';' : ',';
    }

    private List<String[]> readExcel(InputStream inputStream, List<String> warnings) throws IOException {
        List<String[]> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int width = 0;
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    width = Math.max(width, Math.min(row.getLastCellNum(), 64));
                }
            }
            for (int r = 0; r <= sheet.getLastRowNum() && rows.size() < MAX_ROWS; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    rows.add(new String[0]);
                    continue;
                }
                String[] cells = new String[Math.max(width, 1)];
                for (int c = 0; c < cells.length; c++) {
                    Cell cell = row.getCell(c);
                    cells[c] = cell == null ? "" : clean(formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
            if (sheet.getLastRowNum() + 1 > MAX_ROWS) {
                warnings.add("文件超过 " + MAX_ROWS + " 行，只解析前 " + MAX_ROWS + " 行");
            }
        }
        return rows;
    }

    /** 手写 CSV 解析：支持引号包裹、字段内逗号与换行、双引号转义。 */
    private List<String[]> parseCsv(String text, char delimiter) {
        List<String[]> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String source = text.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == delimiter) {
                cells.add(clean(cell.toString()));
                cell.setLength(0);
            } else if (ch == '\n') {
                cells.add(clean(cell.toString()));
                cell.setLength(0);
                rows.add(cells.toArray(new String[0]));
                cells.clear();
                if (rows.size() >= MAX_ROWS) {
                    return rows;
                }
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !cells.isEmpty()) {
            cells.add(clean(cell.toString()));
            rows.add(cells.toArray(new String[0]));
        }
        return rows;
    }

    /* ---------------- 列识别 ---------------- */

    public boolean supports(String type) {
        return FIELDS.containsKey(type);
    }

    public List<FieldSpec> fields(String type) {
        return FIELDS.getOrDefault(type, List.of());
    }

    /**
     * 识别表头并建立「字段 → 列下标」映射。
     *
     * @param type 导入类型
     * @param rows 原始表格行
     * @return 映射结果
     */
    public Mapping map(String type, List<String[]> rows) {
        List<FieldSpec> specs = fields(type);
        int headerRow = detectHeaderRow(specs, rows);
        Map<String, List<Integer>> indexes = new LinkedHashMap<>();
        List<ImportColumn> columns = new ArrayList<>();
        String[] header = null;
        if (headerRow >= 0) {
            header = rows.get(headerRow);
            Set<Integer> used = new HashSet<>();
            for (FieldSpec spec : specs) {
                List<Integer> picked = pickColumns(spec, header, used);
                if (picked.isEmpty()) {
                    continue;
                }
                indexes.put(spec.field(), picked);
                if (!"fee".equals(spec.field()) && !"amount".equals(spec.field())) {
                    used.addAll(picked);
                }
                columns.add(new ImportColumn(spec.field(), spec.label(), joinSource(header, picked)));
            }
        } else {
            indexes.putAll(positional(type));
            for (FieldSpec spec : specs) {
                List<Integer> picked = indexes.get(spec.field());
                if (picked != null && !picked.isEmpty()) {
                    columns.add(new ImportColumn(spec.field(), spec.label(), "第 " + (picked.get(0) + 1) + " 列"));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (FieldSpec spec : specs) {
            if (spec.required() && !indexes.containsKey(spec.field())) {
                missing.add(spec.label());
            }
        }
        if (TYPE_TRADES.equals(type) && !indexes.containsKey("price") && !indexes.containsKey("amount")) {
            missing.add("成交价格");
        }
        return new Mapping(indexes, columns, missing, headerRow, header);
    }

    /** 找到最像表头的那一行；都没有就返回 -1，改用固定列顺序。 */
    private int detectHeaderRow(List<FieldSpec> specs, List<String[]> rows) {
        int required = (int) specs.stream().filter(FieldSpec::required).count();
        int threshold = Math.min(3, Math.max(1, required));
        int bestRow = -1;
        int bestCoverage = 0;
        for (int r = 0; r < Math.min(rows.size(), 10); r++) {
            String[] row = rows.get(r);
            int nonBlank = 0;
            for (String cell : row) {
                if (!clean(cell).isEmpty()) {
                    nonBlank++;
                }
            }
            if (nonBlank < 2) {
                continue;
            }
            int coverage = 0;
            for (FieldSpec spec : specs) {
                if (spec.required() && pickBest(spec, row, new HashSet<>()) >= 0) {
                    coverage++;
                }
            }
            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                bestRow = r;
            }
        }
        return bestCoverage >= threshold ? bestRow : -1;
    }

    private List<Integer> pickColumns(FieldSpec spec, String[] header, Set<Integer> used) {
        List<Integer> result = new ArrayList<>();
        if ("fee".equals(spec.field())) {
            for (int c = 0; c < header.length; c++) {
                if (score(spec, header[c]) > 0) {
                    result.add(c);
                }
            }
            return result;
        }
        int best = pickBest(spec, header, used);
        if (best >= 0) {
            result.add(best);
        }
        return result;
    }

    private int pickBest(FieldSpec spec, String[] header, Set<Integer> used) {
        int bestColumn = -1;
        int bestScore = 0;
        for (int c = 0; c < header.length; c++) {
            if (used.contains(c)) {
                continue;
            }
            int score = score(spec, header[c]);
            if (score > bestScore) {
                bestScore = score;
                bestColumn = c;
            }
        }
        return bestColumn;
    }

    private int score(FieldSpec spec, String raw) {
        String header = normalizeHeader(raw);
        if (header.isEmpty()) {
            return 0;
        }
        for (String blocked : spec.blocked()) {
            if (header.contains(blocked)) {
                return 0;
            }
        }
        int best = 0;
        for (String keyword : spec.keywords()) {
            if (header.equals(keyword)) {
                best = Math.max(best, 100 + keyword.length());
            } else if (header.contains(keyword)) {
                best = Math.max(best, 60 + keyword.length());
            } else if (keyword.contains(header) && header.length() >= 2) {
                best = Math.max(best, 20 + header.length());
            }
        }
        return best;
    }

    private String normalizeHeader(String raw) {
        String s = clean(raw);
        s = s.replaceAll("[（(].*?[)）]", "");
        return s.replace(" ", "").replace("\u00a0", "");
    }

    private String joinSource(String[] header, List<Integer> columns) {
        List<String> names = new ArrayList<>();
        for (int c : columns) {
            String name = clean(header[c]);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join(" + ", names);
    }

    /** 没有表头时的兜底列顺序，和模板保持一致。 */
    private Map<String, List<Integer>> positional(String type) {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        if (TYPE_TRADES.equals(type)) {
            put(map, "tradeDate", 0);
            put(map, "symbol", 1);
            put(map, "name", 2);
            put(map, "side", 3);
            put(map, "price", 4);
            put(map, "quantity", 5);
            put(map, "fee", 6);
            put(map, "reason", 8);
            put(map, "sellCondition", 9);
        } else if (TYPE_DAILY.equals(type)) {
            put(map, "tradeDate", 0);
            put(map, "equity", 1);
            put(map, "cash", 2);
            put(map, "note", 3);
        } else {
            put(map, "symbol", 0);
            put(map, "name", 1);
            put(map, "quantity", 2);
            put(map, "avgCost", 3);
            put(map, "closePrice", 4);
        }
        return map;
    }

    private void put(Map<String, List<Integer>> map, String field, int index) {
        map.put(field, List.of(index));
    }

    /* ---------------- 行归一化 ---------------- */

    /**
     * 把一行单元格转换归一化字段。
     *
     * @param type    导入类型
     * @param cells   单元格
     * @param mapping 列映射
     * @param rowNo   源文件行号
     * @return 归一化结果；有错时 {@code error} 为原因
     */
    public ParsedRow normalize(String type, String[] cells, Mapping mapping, int rowNo) {
        Map<String, String> data = new LinkedHashMap<>();
        try {
            if (TYPE_TRADES.equals(type)) {
                normalizeTrade(cells, mapping, data);
            } else if (TYPE_DAILY.equals(type)) {
                normalizeDaily(cells, mapping, data);
            } else {
                normalizePosition(cells, mapping, data);
            }
            return new ParsedRow(rowNo, data, null);
        } catch (RuntimeException e) {
            return new ParsedRow(rowNo, data, e.getMessage());
        }
    }

    private void normalizeTrade(String[] cells, Mapping mapping, Map<String, String> data) {
        LocalDate date = parseDate(text(cells, mapping, "tradeDate"));
        if (date == null) {
            throw new IllegalArgumentException("交易日期无法识别");
        }
        String symbol = cleanSymbol(text(cells, mapping, "symbol"));
        if (symbol.isEmpty()) {
            throw new IllegalArgumentException("证券代码为空");
        }
        String name = text(cells, mapping, "name");
        if (name.isEmpty()) {
            name = symbol;
        }
        BigDecimal quantityValue = decimal(cells, mapping, "quantity");
        if (quantityValue == null) {
            throw new IllegalArgumentException("成交数量无法识别");
        }
        int quantity = quantityValue.abs().setScale(0, RoundingMode.HALF_UP).intValue();
        if (quantity <= 0) {
            throw new IllegalArgumentException("成交数量必须大于 0");
        }
        String side = parseSide(text(cells, mapping, "side"), cells);
        if (side == null && quantityValue.signum() < 0) {
            side = "SELL";
        }
        if (side == null) {
            throw new IllegalArgumentException("买卖方向无法识别（需包含买入/卖出）");
        }
        BigDecimal price = decimal(cells, mapping, "price");
        if (price == null) {
            BigDecimal amount = decimal(cells, mapping, "amount");
            if (amount != null) {
                price = amount.abs().divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
            }
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("成交价格缺失或不大于 0");
        }
        BigDecimal fee = sumFee(cells, mapping);
        String reason = text(cells, mapping, "reason");
        String sellCondition = text(cells, mapping, "sellCondition");

        data.put("tradeDate", date.toString());
        data.put("symbol", symbol);
        data.put("name", name);
        data.put("side", side);
        data.put("price", plain(price));
        data.put("quantity", String.valueOf(quantity));
        data.put("fee", plain(fee));
        data.put("reason", reason);
        data.put("sellCondition", sellCondition);
    }

    private void normalizeDaily(String[] cells, Mapping mapping, Map<String, String> data) {
        LocalDate date = parseDate(text(cells, mapping, "tradeDate"));
        if (date == null) {
            throw new IllegalArgumentException("日期无法识别");
        }
        BigDecimal equity = decimal(cells, mapping, "equity");
        if (equity == null || equity.signum() < 0) {
            throw new IllegalArgumentException("总资产缺失或为负数");
        }
        BigDecimal cash = decimal(cells, mapping, "cash");
        if (cash == null) {
            cash = BigDecimal.ZERO;
        }
        data.put("tradeDate", date.toString());
        data.put("equity", plain(equity));
        data.put("cash", plain(cash));
        data.put("note", text(cells, mapping, "note"));
    }

    private void normalizePosition(String[] cells, Mapping mapping, Map<String, String> data) {
        String symbol = cleanSymbol(text(cells, mapping, "symbol"));
        if (symbol.isEmpty()) {
            throw new IllegalArgumentException("证券代码为空");
        }
        BigDecimal quantityValue = decimal(cells, mapping, "quantity");
        if (quantityValue == null || quantityValue.signum() <= 0) {
            throw new IllegalArgumentException("持仓数量必须大于 0（已清仓的标的不用导入）");
        }
        int quantity = quantityValue.setScale(0, RoundingMode.HALF_UP).intValue();
        BigDecimal avgCost = decimal(cells, mapping, "avgCost");
        BigDecimal closePrice = decimal(cells, mapping, "closePrice");
        if (avgCost == null) {
            avgCost = closePrice;
        }
        if (avgCost == null) {
            throw new IllegalArgumentException("成本价缺失（且没有最新价可替代）");
        }
        String name = text(cells, mapping, "name");
        if (name.isEmpty()) {
            name = symbol;
        }
        LocalDate date = parseDate(text(cells, mapping, "snapshotDate"));
        if (date == null) {
            date = LocalDate.now();
        }
        data.put("snapshotDate", date.toString());
        data.put("symbol", symbol);
        data.put("name", name);
        data.put("quantity", String.valueOf(quantity));
        data.put("avgCost", plain(avgCost));
        data.put("closePrice", closePrice == null ? "" : plain(closePrice));
    }

    /* ---------------- 单元格工具 ---------------- */

    /** 供提交阶段复用同一套日期解析。 */
    public LocalDate toDate(String raw) {
        return parseDate(raw);
    }

    /** 供提交阶段复用同一套数字解析。 */
    public BigDecimal toNumber(String raw) {
        return parseNumber(raw);
    }

    private String text(String[] cells, Mapping mapping, String field) {
        List<Integer> columns = mapping.indexes().get(field);
        if (columns == null || columns.isEmpty()) {
            return "";
        }
        int index = columns.get(0);
        return index < cells.length ? clean(cells[index]) : "";
    }

    private BigDecimal decimal(String[] cells, Mapping mapping, String field) {
        List<Integer> columns = mapping.indexes().get(field);
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        int index = columns.get(0);
        return index < cells.length ? parseNumber(cells[index]) : null;
    }

    private BigDecimal sumFee(String[] cells, Mapping mapping) {
        List<Integer> columns = mapping.indexes().get("fee");
        BigDecimal total = BigDecimal.ZERO;
        if (columns == null) {
            return total;
        }
        for (int index : columns) {
            if (index < cells.length) {
                BigDecimal value = parseNumber(cells[index]);
                if (value != null) {
                    total = total.add(value.abs());
                }
            }
        }
        return total;
    }

    private String parseSide(String raw, String[] cells) {
        String direct = sideOf(raw);
        if (direct != null) {
            return direct;
        }
        // 有的券商把方向放在「业务名称」「摘要」里，取短文本兜底
        for (String cell : cells) {
            String value = clean(cell);
            if (value.length() <= 8) {
                String side = sideOf(value);
                if (side != null) {
                    return side;
                }
            }
        }
        return null;
    }

    private String sideOf(String raw) {
        String value = clean(raw).toUpperCase(Locale.ROOT).replace(" ", "");
        if (value.isEmpty()) {
            return null;
        }
        if (value.equals("B") || value.equals("BUY") || value.contains("买入") || value.equals("买")) {
            return "BUY";
        }
        if (value.equals("S") || value.equals("SELL") || value.contains("卖出") || value.equals("卖")) {
            return "SELL";
        }
        return null;
    }

    private String cleanSymbol(String raw) {
        String symbol = clean(raw).replace("'", "").replace("=", "").replace("\"", "");
        return symbol.toUpperCase(Locale.ROOT);
    }

    private LocalDate parseDate(String raw) {
        String value = clean(raw);
        if (value.isEmpty()) {
            return null;
        }
        int space = value.indexOf(' ');
        if (space > 0) {
            value = value.substring(0, space);
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        // Excel 未设日期格式时是 5 位序列号，基准 1899-12-30
        if (value.matches("\\d{5}(\\.0+)?")) {
            return LocalDate.of(1899, 12, 30).plusDays(Long.parseLong(value.substring(0, 5)));
        }
        return null;
    }

    private BigDecimal parseNumber(String raw) {
        String value = clean(raw);
        if (value.isEmpty()) {
            return null;
        }
        value = value.replace(",", "").replace("，", "").replace("¥", "").replace("￥", "")
                .replace("元", "").replace("股", "").replace("%", "").replace(" ", "").replace("\u00a0", "");
        if (value.isEmpty() || value.equals("-") || value.equals("--") || value.equals("—")
                || value.equals("/") || value.equalsIgnoreCase("N/A")) {
            return null;
        }
        boolean negative = value.startsWith("(") && value.endsWith(")");
        if (negative) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            BigDecimal number = new BigDecimal(value);
            return negative ? number.negate() : number;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\uFEFF", "").replace("\u00a0", " ").trim();
    }
}
