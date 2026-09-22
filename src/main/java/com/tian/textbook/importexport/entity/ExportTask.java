package com.tian.textbook.importexport.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 导出任务（export_task，Q16/W18）：queued → running → done/failed/expired；
 * 一次性下载 token 单次有效、默认 10 分钟过期，文件保留 24 小时。
 */
@Data
@TableName(value = "export_task", autoResultMap = true)
public class ExportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** order/signature/student/notice/supplier */
    private String bizType;

    @TableField(value = "params_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> paramsJson;

    private Integer rowEstimate;

    /** 服务器内部路径：不下发前端（@JsonIgnore） */
    @JsonIgnore
    private String filePath;

    /**
     * 一次性下载 token（首次下载后置空）。
     *
     * <p>刻意<b>保留</b>在 JSON 响应中（与 {@link #filePath} 不同）：异步导出的 token 是在任务
     * 完成后才生成的，前端只能从轮询 `GET /api/export-task/{id}` 的响应里拿到它——
     * 加 {@code @JsonIgnore} 会直接切断「轮询 → 下载」链路。
     * 越权读取由接口层的归属校验兜住（{@code getTaskForUser} / {@code getSupplierTask}：
     * 非 ADMIN 只能读本人创建的任务，失败统一 404，不通过状态码差异泄露任务存在性）。</p>
     */
    private String downloadToken;

    private LocalDateTime tokenExpireAt;

    /** 文件保留截止（24 小时） */
    private LocalDateTime expiresAt;

    private String status;

    private Integer progressPct;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
