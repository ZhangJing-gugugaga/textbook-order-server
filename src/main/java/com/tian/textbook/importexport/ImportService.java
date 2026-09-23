package com.tian.textbook.importexport;

import com.tian.textbook.importexport.dto.ImportPreviewResponse;
import com.tian.textbook.importexport.entity.ImportBatch;
import org.springframework.web.multipart.MultipartFile;

/**
 * 导入中心服务（SPEC §10：异步批次解析）。
 *
 * <p>接口定义于此，实现位于 importexport 模块；其他模块（教材/任课/名单/异动）的
 * 导入端点依赖本接口启动异步导入。</p>
 *
 * <p>bizType ∈ student / teacher / textbook / teacher_course / change（SPEC §10 模板清单）。</p>
 */
public interface ImportService {

    /**
     * 启动异步导入（落盘 + 批次落库 + @Async 解析）。
     *
     * @param bizType    业务类型
     * @param semesterId 目标学期（draft 学期；textbook 导入可传 null）
     * @param file       .xlsx 文件（≤10MB，魔数校验）
     * @return batchId（前端轮询 GET /api/batch/{batchId}）
     */
    default Long startImport(String bizType, Long semesterId, MultipartFile file) {
        return startImport(bizType, semesterId, file, false);
    }

    /**
     * 启动异步导入（含局部名单门禁，B13）。
     *
     * <p>学生名单导入会按「文件内该班去重人数」重算班级人数（= 教师填报数量上限）。
     * 若下调幅度命中阈值（比例 &gt; 配置值 且 不少于配置人数下限），视为疑似局部名单：
     * 未带 {@code confirmClassSizeShrink=true} 时**直接 409 且不建批次**，message 回显逐班
     * diff 与两条可行路径（改用完整名单，或确认后重提）。</p>
     *
     * @param confirmClassSizeShrink 调用方是否已确认「班级人数下调超阈值」
     */
    Long startImport(String bizType, Long semesterId, MultipartFile file, boolean confirmClassSizeShrink);

    /**
     * 导入预览（只读扫描，不落库、不建批次）：班级人数 diff / 将新建账号数 / 将停用账号数。
     *
     * <p>与真实导入共用同一套解析与行校验，故预览结果即「此刻导入会发生什么」。</p>
     */
    ImportPreviewResponse previewImport(String bizType, Long semesterId, MultipartFile file);

    /** 批次进度（total/ok/error/progress_pct/status） */
    ImportBatch getBatch(Long batchId);

    /**
     * 批次进度（归属校验）：非 ADMIN 只能读自己发起的批次，否则按 404 处理。
     * 对外端点一律用本方法，{@link #getBatch} 仅供内部与测试使用。
     */
    ImportBatch getBatchForUser(Long batchId);

    /** 错误明细文件路径（无错误返回 null） */
    String errorFilePath(Long batchId);

    /**
     * 下载用错误明细文件路径：含归属校验，且区分「没有错误明细」与「明细已过期清理」。
     * 对外端点用本方法。
     */
    String errorFilePathForDownload(Long batchId);
}
