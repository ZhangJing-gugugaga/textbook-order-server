package com.tian.textbook.importexport.support;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 下载响应工具（模板/错误明细/导出文件流式写出；Content-Disposition 附件）。
 */
public final class DownloadSupport {

    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private DownloadSupport() {
    }

    /** 设置 xlsx 附件响应头（RFC 5987 filename*，避免中文乱码） */
    public static void attachXlsx(HttpServletResponse response, String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }

    /** 流式写文件到响应（文件不存在 → 404） */
    public static void writeFile(HttpServletResponse response, Path file, String fileName) throws IOException {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在或已被清理");
        }
        attachXlsx(response, fileName);
        try (InputStream in = Files.newInputStream(file)) {
            copy(in, response.getOutputStream());
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }
}
