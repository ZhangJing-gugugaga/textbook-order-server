package com.tian.textbook.importexport;

import com.tian.textbook.importexport.entity.ExportTask;

import java.io.OutputStream;
import java.util.Map;

/**
 * 导出中心服务（SPEC §10：同步流式 / 异步任务 + 一次性下载 token）。
 *
 * <p>接口定义于此，实现位于 importexport 模块；各角色导出端点依赖本接口。</p>
 *
 * <p>bizType ∈ order / signature / student / notice / supplier。</p>
 */
public interface ExportService {

    /** 预估行数是否走异步（> export.sync_row_threshold，Q16） */
    boolean shouldGoAsync(int rowEstimate);

    /** 创建异步导出任务（queued → running → done） */
    ExportTask createAsyncTask(String bizType, Map<String, Object> params, int rowEstimate);

    /** 任务进度 */
    ExportTask getTask(Long taskId);

    /** 同步流式导出（预估行数 ≤ 阈值；直接写 OutputStream） */
    void writeSync(String bizType, Map<String, Object> params, OutputStream out);

    /**
     * 校验并消费一次性下载 token（单次有效：首次下载后置空；过期/失效 → 410）。
     *
     * @return 可下载的导出任务（filePath 已生成）
     */
    ExportTask claimDownload(Long taskId, String token);

    /**
     * 供货商侧任务读取：在归属校验（created_by=本人，ADMIN 放行）之上追加 bizType=supplier 白名单。
     *
     * <p>export_task 与内部导出任务共表且主键自增，仅按 id 取任务时任一供货商账号即可
     * 枚举读取内部任务（教师征订/学生选购/通知汇总）元数据，叠加 token 下发即越权下载。</p>
     */
    ExportTask getSupplierTask(Long taskId);

    /** 供货商一次性下载（归属校验 + bizType 白名单 + token 校验消费）。 */
    ExportTask claimSupplierDownload(Long taskId, String token);
}
