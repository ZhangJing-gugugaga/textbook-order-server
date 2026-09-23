package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 通知汇总导出行（C4 扩展字段集，W7）：学号/工号、姓名、角色、学院、班级、
 * 第 1~5 轮发送时间与状态、确认状态、确认时间。
 *
 * <p>各轮明细由 Service 层按 user 透视（notice_record 一行一轮）。</p>
 */
@Data
@NoArgsConstructor
public class NoticeSummaryExportRow {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 一轮发送记录（时间 + 状态） */
    public record Round(String sentAt, String sendStatus) {
    }

    @ExcelProperty("学号/工号")
    private String userNo;

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("角色")
    private String role;

    @ExcelProperty("学院")
    private String collegeName;

    @ExcelProperty("班级")
    private String className;

    /** 渠道（BE-5f / D4 口径）：订阅消息+弹窗 / 仅弹窗（未授权）/ 仅弹窗 */
    @ExcelProperty("渠道")
    private String channel;

    @ExcelProperty("第1轮发送时间")
    private String round1SentAt;

    @ExcelProperty("第1轮发送状态")
    private String round1Status;

    @ExcelProperty("第2轮发送时间")
    private String round2SentAt;

    @ExcelProperty("第2轮发送状态")
    private String round2Status;

    @ExcelProperty("第3轮发送时间")
    private String round3SentAt;

    @ExcelProperty("第3轮发送状态")
    private String round3Status;

    @ExcelProperty("第4轮发送时间")
    private String round4SentAt;

    @ExcelProperty("第4轮发送状态")
    private String round4Status;

    @ExcelProperty("第5轮发送时间")
    private String round5SentAt;

    @ExcelProperty("第5轮发送状态")
    private String round5Status;

    @ExcelProperty("确认状态")
    private String confirmStatus;

    @ExcelProperty("确认时间")
    private String confirmedAt;

    /** 按 user 透视结果构造一行（channel = 渠道列，BE-5f） */
    public static NoticeSummaryExportRow of(String userNo, String name, String role,
                                            String collegeName, String className,
                                            String channel,
                                            Map<Integer, Round> rounds,
                                            LocalDateTime confirmedAt) {
        NoticeSummaryExportRow row = new NoticeSummaryExportRow();
        row.userNo = blankToEmpty(userNo);
        row.name = blankToEmpty(name);
        row.role = blankToEmpty(role);
        row.collegeName = blankToEmpty(collegeName);
        row.className = blankToEmpty(className);
        row.channel = blankToEmpty(channel);
        row.round1SentAt = sentAt(rounds, 1);
        row.round1Status = status(rounds, 1);
        row.round2SentAt = sentAt(rounds, 2);
        row.round2Status = status(rounds, 2);
        row.round3SentAt = sentAt(rounds, 3);
        row.round3Status = status(rounds, 3);
        row.round4SentAt = sentAt(rounds, 4);
        row.round4Status = status(rounds, 4);
        row.round5SentAt = sentAt(rounds, 5);
        row.round5Status = status(rounds, 5);
        row.confirmedAt = confirmedAt == null ? "" : FMT.format(confirmedAt);
        row.confirmStatus = confirmedAt == null ? "未确认" : "已确认";
        return row;
    }

    private static String sentAt(Map<Integer, Round> rounds, int roundNo) {
        Round round = rounds.get(roundNo);
        return round == null || round.sentAt() == null ? "" : round.sentAt();
    }

    private static String status(Map<Integer, Round> rounds, int roundNo) {
        Round round = rounds.get(roundNo);
        return round == null || round.sendStatus() == null ? "" : round.sendStatus();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
