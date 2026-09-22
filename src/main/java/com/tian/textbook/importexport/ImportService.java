package com.tian.textbook.importexport;

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
    Long startImport(String bizType, Long semesterId, MultipartFile file);

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
