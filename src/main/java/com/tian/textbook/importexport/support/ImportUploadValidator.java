package com.tian.textbook.importexport.support;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 导入上传前置校验（SPEC §10 / PRD 文件安全）：非空、后缀 .xlsx、魔数 PK\u0003\u0004、
 * 大小 ≤ import.max_file_mb（业务配置，ConfigService 读取）。
 */
public final class ImportUploadValidator {

    /** xlsx = zip 容器，文件头魔数 PK\x03\x04（.xls 为 OLE2 D0CF11E0，拒绝） */
    private static final byte[] XLSX_MAGIC = {(byte) 'P', (byte) 'K', 0x03, 0x04};

    private ImportUploadValidator() {
    }

    public static void validate(MultipartFile file, int maxFileMb) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID, "请选择要上传的文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".xlsx")) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID, "仅支持 .xlsx 文件");
        }
        if (file.getSize() > maxFileMb * 1024L * 1024L) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    String.format("文件超过大小上限（%dMB）", maxFileMb));
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(4);
            if (head.length < 4 || head[0] != XLSX_MAGIC[0] || head[1] != XLSX_MAGIC[1]
                    || head[2] != XLSX_MAGIC[2] || head[3] != XLSX_MAGIC[3]) {
                throw new BizException(ErrorCode.FILE_TYPE_INVALID, "文件内容不是有效的 xlsx 文件");
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID, "文件读取失败");
        }
    }
}
