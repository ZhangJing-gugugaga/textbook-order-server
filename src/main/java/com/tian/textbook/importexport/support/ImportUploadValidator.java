package com.tian.textbook.importexport.support;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 导入上传前置校验（SPEC §10 / PRD 文件安全）：非空、后缀 .xlsx、魔数 PK\u0003\u0004、
 * 大小 ≤ import.max_file_mb（业务配置，ConfigService 读取），以及<b>解压体积与条目数上限</b>。
 *
 * <p>只校验魔数与压缩包大小是不够的：xlsx 本质是 zip，一个 10MB 的文件可以解压出数 GB
 * （zip bomb），EasyExcel 逐行解析会直接把 JVM 拖入 OOM。</p>
 *
 * <p>实现要点：用 commons-compress 的 {@link ZipArchiveInputStream} 而非 JDK 的
 * {@link java.util.zip.ZipInputStream}——POI 5.x 写出的 xlsx 使用数据描述符（data descriptor）
 * 流式写条目，JDK 实现在这种结构上会抛 {@code invalid entry size}。
 * 另外<b>累计实际解压字节数</b>（而非条目声明的大小）：流式条目的声明大小可能为 -1，
 * 而实际字节数才是 OOM 的真实来源；累计到上限即中止。</p>
 */
public final class ImportUploadValidator {

    /** xlsx = zip 容器，文件头魔数 PK\x03\x04（.xls 为 OLE2 D0CF11E0，拒绝） */
    private static final byte[] XLSX_MAGIC = {(byte) 'P', (byte) 'K', 0x03, 0x04};

    /** 解压后总体积上限（正常 xlsx 约为压缩包的 5~10 倍） */
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

    /** zip 条目数上限（正常 xlsx 条目数为几十） */
    private static final int MAX_ZIP_ENTRIES = 2_000;

    /** 单条目解压体积上限（防单个超大 sheet） */
    private static final long MAX_SINGLE_ENTRY_BYTES = 128L * 1024 * 1024;

    /** 解压计数缓冲区（8KB，仅用于累计字节数，不保留内容） */
    private static final int DRAIN_BUFFER = 8 * 1024;

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
        assertArchiveWithinLimits(file);
    }

    /** 遍历 zip 条目并累计实际解压体积，超限即中止。 */
    private static void assertArchiveWithinLimits(MultipartFile file) {
        long totalUncompressed = 0;
        int entries = 0;
        byte[] drain = new byte[DRAIN_BUFFER];
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(file.getInputStream(), "UTF-8", true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new BizException(ErrorCode.FILE_TOO_LARGE,
                            "文件内部条目过多，疑似非法 xlsx，请另存后重试");
                }
                long entryBytes = 0;
                int read;
                while ((read = zip.read(drain)) > 0) {
                    entryBytes += read;
                    totalUncompressed += read;
                    if (entryBytes > MAX_SINGLE_ENTRY_BYTES || totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                        throw new BizException(ErrorCode.FILE_TOO_LARGE,
                                "文件解压后体积超过上限（可能为压缩炸弹），请拆分后分批导入");
                    }
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID, "文件解析失败，请确认是有效的 xlsx");
        }
    }
}
