package com.tian.textbook.importexport.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tian.textbook.importexport.excel.NoticeSummaryExportRow;
import com.tian.textbook.importexport.excel.OrderExportRow;
import com.tian.textbook.importexport.excel.StudentSummaryExportRow;
import com.tian.textbook.importexport.excel.SupplierExportRow;
import com.tian.textbook.importexport.template.TemplateService;
import com.tian.textbook.notify.mapper.NoticeRecordMapper;
import com.tian.textbook.common.util.MapKeys;
import com.tian.textbook.order.dto.StudentOrderSummaryRow;
import com.tian.textbook.order.mapper.OrderFormItemMapper;
import com.tian.textbook.order.mapper.StudentOrderItemMapper;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导出内容生成（SPEC §10 / W18）：教师征订明细、秘书签字版、学生选购汇总、通知汇总、供货商清单。
 *
 * <p>同步流式与异步任务共用（writeSync / ExportAsyncService 都走这里），保证同一数据源、
 * 同一版式。所有查询带 deleted=0（Mapper 层 SQL 已保证）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportDataWriter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_SHEET_NAME = 31;

    private static final List<String> DEFAULT_SIGNATURE_HEADERS =
            List.of("学院", "教师", "工号", "课程", "班级", "ISBN", "书名", "数量", "签字");
    private static final String DEFAULT_SIGNATURE_TITLE = "（学院名称）（学期名称）教材征订签字版";
    private static final List<String> DEFAULT_SIGNATURE_LINES =
            List.of("学院盖章：______", "教材室签字：______", "日期：______");

    private final OrderFormItemMapper orderFormItemMapper;
    private final StudentOrderItemMapper studentOrderItemMapper;
    private final NoticeRecordMapper noticeRecordMapper;
    private final CollegeMapper collegeMapper;
    private final SemesterMapper semesterMapper;
    private final TemplateService templateService;
    private final ObjectMapper objectMapper;

    // ============ 分发（同步流式 / 异步任务共用） ============

    /** 按 bizType 生成内容（params 含 semesterId/collegeId/taskId） */
    public void write(String bizType, Map<String, Object> params, OutputStream out) {
        switch (bizType) {
            case "order" -> writeOrder(out, longParam(params, "semesterId"), longParam(params, "collegeId"));
            case "signature" ->
                    writeSignature(out, longParam(params, "semesterId"), longParam(params, "collegeId"));
            case "student" -> writeStudent(out, longParam(params, "semesterId"));
            case "notice" -> writeNotice(out, longParam(params, "taskId"), longParam(params, "semesterId"));
            case "supplier" -> writeSupplier(out, longParam(params, "semesterId"));
            default -> throw new IllegalArgumentException("不支持的导出类型: " + bizType);
        }
    }

    // ============ 教师征订明细（秘书本院/教材室全院） ============

    public void writeOrder(OutputStream out, Long semesterId, Long collegeId) {
        List<Map<String, Object>> items = orderFormItemMapper.selectReviewedItems(semesterId, collegeId);
        List<OrderExportRow> rows = items.stream().map(this::toOrderRow).toList();
        EasyExcel.write(out, OrderExportRow.class).sheet("教师征订明细").doWrite(rows);
    }

    // ============ 秘书本院签字版 ============

    /**
     * 签字版：同 order 数据 + 签字栏。版式取自 templates/secretary-signature.xlsx（存在时：
     * 读取其标题/表头/签字占位行；田老师样张到位后替换模板、代码不改），否则按内置版式生成。
     */
    public void writeSignature(OutputStream out, Long semesterId, Long collegeId) {
        List<Map<String, Object>> items = orderFormItemMapper.selectReviewedItems(semesterId, collegeId);
        SignatureLayout layout = loadSignatureLayout();
        String title = layout.resolveTitle(collegeName(collegeId), semesterName(semesterId));
        List<String> headers = layout.headers();

        // EasyExcel 复杂表头是「列优先」：每个内层 List 是一列（标题单元格 + 表头单元格）
        List<List<String>> columns = new ArrayList<>();
        for (int col = 0; col < headers.size(); col++) {
            String header = headers.get(col);
            columns.add(List.of(col == 0 ? title : "", header));
        }

        List<List<Object>> data = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<Object> row = new ArrayList<>();
            for (String header : headers) {
                row.add(signatureCell(header, item));
            }
            data.add(row);
        }
        for (String line : layout.signatureLines()) {
            List<Object> row = new ArrayList<>();
            row.add(line);
            while (row.size() < headers.size()) {
                row.add("");
            }
            data.add(row);
        }
        EasyExcel.write(out).head(columns).sheet("签字版").doWrite(data);
    }

    private Object signatureCell(String header, Map<String, Object> item) {
        return switch (header) {
            case "学院" -> text(item.get("collegeName"));
            case "教师" -> text(item.get("teacherName"));
            case "工号" -> text(item.get("teacherNo"));
            case "课程" -> text(item.get("courseName"));
            case "班级" -> text(item.get("className"));
            case "ISBN" -> text(item.get("isbn"));
            case "书名" -> text(item.get("title"));
            case "数量" -> intValue(item.get("quantity"));
            default -> "";
        };
    }

    // ============ 学生选购汇总（参考用量，W18） ============

    public void writeStudent(OutputStream out, Long semesterId) {
        List<StudentOrderSummaryRow> raw = studentOrderItemMapper.selectSummaryRows(semesterId);
        Map<String, StudentAgg> grouped = new LinkedHashMap<>();
        for (StudentOrderSummaryRow row : raw) {
            String[] snapshot = parseSnapshot(row.submitSnapshot());
            String key = snapshot[0] + "|" + snapshot[1] + "|" + row.isbn() + "|" + row.title();
            StudentAgg agg = grouped.computeIfAbsent(key,
                    k -> new StudentAgg(snapshot[0], snapshot[1], row.isbn(), row.title()));
            if (row.studentId() != null) {
                agg.students.add(row.studentId());
            }
            agg.totalQuantity += row.quantity() == null ? 0 : row.quantity();
        }
        List<StudentSummaryExportRow> rows = grouped.values().stream()
                .sorted((a, b) -> {
                    int byCollege = a.collegeName.compareTo(b.collegeName);
                    if (byCollege != 0) {
                        return byCollege;
                    }
                    int byClass = a.className.compareTo(b.className);
                    return byClass != 0 ? byClass : a.title.compareTo(b.title);
                })
                .map(agg -> new StudentSummaryExportRow(agg.collegeName, agg.className, agg.isbn,
                        agg.title, agg.students.size(), agg.totalQuantity))
                .toList();
        EasyExcel.write(out, StudentSummaryExportRow.class).sheet("学生选购汇总").doWrite(rows);
    }

    // ============ 通知汇总（按 user 透视各轮） ============

    public void writeNotice(OutputStream out, Long taskId, Long semesterId) {
        List<Map<String, Object>> raw = noticeRecordMapper.selectTaskSummaryRows(taskId, semesterId);
        Map<Long, NoticeAgg> byUser = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            // 列标签取值一律走 MapKeys.pick：H2 会把别名转成小写、MySQL 保留原样，
            // 直接用 row.get("userId") 在 H2 上取不到值（导出会静默变成空表）
            Long userId = longValue(MapKeys.pick(row, "userId"));
            if (userId == null) {
                continue;
            }
            NoticeAgg agg = byUser.computeIfAbsent(userId, k -> new NoticeAgg(
                    text(MapKeys.pick(row, "userNo")), text(MapKeys.pick(row, "name")),
                    roleLabel(text(MapKeys.pick(row, "role"))), text(MapKeys.pick(row, "collegeName")),
                    text(MapKeys.pick(row, "className"))));
            Object roundNo = MapKeys.pick(row, "roundNo");
            if (roundNo instanceof Number n && n.intValue() >= 1 && n.intValue() <= 5) {
                agg.rounds.put(n.intValue(), new NoticeSummaryExportRow.Round(
                        formatDateTime(MapKeys.pick(row, "sentAt")), text(MapKeys.pick(row, "sendStatus"))));
            }
            Object confirmedAt = MapKeys.pick(row, "confirmedAt");
            if (confirmedAt instanceof LocalDateTime dt
                    && (agg.confirmedAt == null || dt.isAfter(agg.confirmedAt))) {
                agg.confirmedAt = dt;
            }
        }
        List<NoticeSummaryExportRow> rows = byUser.values().stream()
                .map(agg -> NoticeSummaryExportRow.of(agg.userNo, agg.name, agg.role,
                        agg.collegeName, agg.className, channelOf(agg.rounds),
                        agg.rounds, agg.confirmedAt))
                .toList();
        EasyExcel.write(out, NoticeSummaryExportRow.class).sheet("通知汇总").doWrite(rows);
    }

    // ============ 供货商清单（一学院一 sheet） ============

    public void writeSupplier(OutputStream out, Long semesterId) {
        List<Map<String, Object>> items = orderFormItemMapper.selectReviewedItems(semesterId, null);
        Map<String, List<SupplierExportRow>> byCollege = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String college = text(item.get("collegeName"));
            if (college.isEmpty()) {
                college = "未分配学院";
            }
            byCollege.computeIfAbsent(college, k -> new ArrayList<>()).add(new SupplierExportRow(
                    text(item.get("title")), text(item.get("isbn")),
                    intValue(item.get("quantity")), text(item.get("teacherName")), college));
        }
        ExcelWriter writer = EasyExcel.write(out).build();
        try {
            if (byCollege.isEmpty()) {
                writer.write(new ArrayList<SupplierExportRow>(),
                        EasyExcel.writerSheet(0, "无数据").head(SupplierExportRow.class).build());
            } else {
                Set<String> usedSheetNames = new HashSet<>();
                int index = 0;
                for (Map.Entry<String, List<SupplierExportRow>> entry : byCollege.entrySet()) {
                    WriteSheet sheet = EasyExcel.writerSheet(index++, sheetName(entry.getKey(), usedSheetNames))
                            .head(SupplierExportRow.class).build();
                    writer.write(entry.getValue(), sheet);
                }
            }
        } finally {
            writer.finish();
        }
    }

    // ============ 版式/转换工具 ============

    private SignatureLayout loadSignatureLayout() {
        try (InputStream in = templateService.openClasspathTemplate(TemplateService.SIGNATURE_FILE)) {
            if (in == null) {
                return SignatureLayout.DEFAULT;
            }
            // headRowNumber(0)：标题行/表头行/签字行全部按数据读出（版式提取用）
            List<Map<Integer, String>> rows = EasyExcel.read(in).sheet().headRowNumber(0).doReadSync();
            return SignatureLayout.from(rows);
        } catch (Exception e) {
            log.warn("签字版模板读取失败，使用内置版式: {}", e.getMessage());
            return SignatureLayout.DEFAULT;
        }
    }

    private OrderExportRow toOrderRow(Map<String, Object> item) {
        return new OrderExportRow(
                text(item.get("collegeName")),
                text(item.get("teacherName")),
                text(item.get("teacherNo")),
                text(item.get("courseName")),
                text(item.get("className")),
                text(item.get("isbn")),
                text(item.get("title")),
                intValue(item.get("quantity")));
    }

    private String collegeName(Long collegeId) {
        if (collegeId == null) {
            return null;
        }
        College college = collegeMapper.selectByIdSoft(collegeId);
        return college == null ? null : college.getName();
    }

    private String semesterName(Long semesterId) {
        if (semesterId == null) {
            return null;
        }
        Semester semester = semesterMapper.selectByIdSoft(semesterId);
        return semester == null ? null : semester.getName();
    }

    /** submit_snapshot JSON → [collegeName, className]（提交时归属快照，异动不影响历史，W15） */
    private String[] parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return new String[]{"", ""};
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return new String[]{
                    node.path("collegeName").asText(""),
                    node.path("className").asText("")};
        } catch (Exception e) {
            return new String[]{"", ""};
        }
    }

    private String roleLabel(String roleCode) {
        if (roleCode.isEmpty()) {
            return "";
        }
        return switch (roleCode) {
            case "STUDENT" -> "学生";
            case "TEACHER" -> "教师";
            case "SECRETARY" -> "秘书";
            case "ADMIN" -> "超管";
            case "SUPPLIER" -> "供货商";
            default -> roleCode;
        };
    }

    private String formatDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dt) {
            return FMT.format(dt);
        }
        if (value instanceof Date date) {
            return FMT.format(LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()));
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    /** sheet 名清洗：去非法字符、截断 31 字符、去重（POI 限制） */
    private String sheetName(String college, Set<String> used) {
        String sanitized = college.replaceAll("[\\\\/?*\\[\\]:']", "").trim();
        if (sanitized.isEmpty()) {
            sanitized = "sheet";
        }
        if (sanitized.length() > MAX_SHEET_NAME) {
            sanitized = sanitized.substring(0, MAX_SHEET_NAME);
        }
        String name = sanitized;
        int suffix = 2;
        while (!used.add(name)) {
            String tail = "-" + suffix++;
            name = sanitized.substring(0, Math.max(1, Math.min(sanitized.length(), MAX_SHEET_NAME - tail.length())))
                    + tail;
        }
        return name;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long longParam(Map<String, Object> params, String key) {
        return params == null ? null : longValue(params.get(key));
    }

    private static final class StudentAgg {
        private final String collegeName;
        private final String className;
        private final String isbn;
        private final String title;
        private final List<Long> students = new ArrayList<>();
        private int totalQuantity;

        private StudentAgg(String collegeName, String className, String isbn, String title) {
            this.collegeName = collegeName;
            this.className = className;
            this.isbn = isbn;
            this.title = title;
        }
    }

    /**
     * 「渠道」列取值（BE-5f，D4 口径三值）：
     * <ul>
     *   <li>{@code 订阅消息+弹窗}：存在 {@code sent} 轮次（订阅消息真的发出去了）；</li>
     *   <li>{@code 仅弹窗（未授权）}：无 {@code sent} 但存在 {@code unauthorized} 轮次
     *       （未授权订阅 → 进线下兜底名单）；</li>
     *   <li>{@code 仅弹窗}：无任何轮次记录（教师/秘书以弹窗为主触达，不写发送记录，Q8）。</li>
     * </ul>
     */
    private static String channelOf(Map<Integer, NoticeSummaryExportRow.Round> rounds) {
        boolean sent = false;
        boolean unauthorized = false;
        for (NoticeSummaryExportRow.Round round : rounds.values()) {
            String status = round.sendStatus();
            if ("sent".equals(status)) {
                sent = true;
            } else if ("unauthorized".equals(status)) {
                unauthorized = true;
            }
        }
        if (sent) {
            return "订阅消息+弹窗";
        }
        return unauthorized ? "仅弹窗（未授权）" : "仅弹窗";
    }

    private static final class NoticeAgg {
        private final String userNo;
        private final String name;
        private final String role;
        private final String collegeName;
        private final String className;
        private final Map<Integer, NoticeSummaryExportRow.Round> rounds = new LinkedHashMap<>();
        private LocalDateTime confirmedAt;

        private NoticeAgg(String userNo, String name, String role, String collegeName, String className) {
            this.userNo = userNo;
            this.name = name;
            this.role = role;
            this.collegeName = collegeName;
            this.className = className;
        }
    }

    /**
     * 签字版版式（模板驱动；模板缺失或结构不符时回退内置默认）。
     *
     * @param title          标题（支持 （学院名称）/（学期名称） 占位替换）
     * @param headers        表头（数据列按表头名对齐）
     * @param signatureLines 末尾签字占位行
     */
    private record SignatureLayout(String title, List<String> headers, List<String> signatureLines) {

        private static final SignatureLayout DEFAULT = new SignatureLayout(
                DEFAULT_SIGNATURE_TITLE, DEFAULT_SIGNATURE_HEADERS, DEFAULT_SIGNATURE_LINES);

        private static SignatureLayout from(List<Map<Integer, String>> rows) {
            if (rows == null || rows.isEmpty()) {
                return DEFAULT;
            }
            List<String> headers = null;
            int headerIndex = -1;
            String title = null;
            for (int i = 0; i < rows.size(); i++) {
                List<String> cells = rowCells(rows.get(i));
                if (headers == null && cells.contains("学院") && cells.contains("教师")) {
                    headers = cells;
                    headerIndex = i;
                    continue;
                }
                if (title == null) {
                    for (String cell : cells) {
                        if (cell != null && !cell.isBlank()) {
                            title = cell.trim();
                            break;
                        }
                    }
                }
            }
            if (headers == null) {
                return DEFAULT;
            }
            List<String> signatureLines = new ArrayList<>();
            for (int i = headerIndex + 1; i < rows.size(); i++) {
                List<String> cells = rowCells(rows.get(i));
                String first = cells.isEmpty() || cells.get(0) == null ? "" : cells.get(0).trim();
                if (!first.isEmpty()) {
                    signatureLines.add(first);
                }
            }
            return new SignatureLayout(
                    title == null || title.isBlank() ? DEFAULT_SIGNATURE_TITLE : title,
                    headers,
                    signatureLines.isEmpty() ? DEFAULT_SIGNATURE_LINES : signatureLines);
        }

        private static List<String> rowCells(Map<Integer, String> row) {
            if (row == null || row.isEmpty()) {
                return List.of();
            }
            int max = row.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            List<String> cells = new ArrayList<>(max + 1);
            for (int i = 0; i <= max; i++) {
                String value = row.get(i);
                cells.add(value == null ? "" : value.trim());
            }
            return cells;
        }

        private String resolveTitle(String collegeName, String semesterName) {
            String resolved = title
                    .replace("（学院名称）", collegeName == null ? "" : collegeName)
                    .replace("（学期名称）", semesterName == null ? "" : semesterName);
            return resolved.isBlank() ? DEFAULT_SIGNATURE_TITLE : resolved;
        }
    }
}
