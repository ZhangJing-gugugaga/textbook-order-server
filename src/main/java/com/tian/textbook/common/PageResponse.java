package com.tian.textbook.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应（SPEC §11：分页参数 page/size 默认 1/20，上限 200；page 从 1 开始）。
 */
public record PageResponse<T>(List<T> list, long page, long size, long total, long totalPages) {

    /** 分页 size 上限（SPEC §11） */
    public static final long MAX_SIZE = 200;

    /**
     * 分页 page 上限。
     *
     * <p>{@code offset = (page - 1) * size} 在 page 极大时会溢出为负，生成的
     * {@code LIMIT n OFFSET -x} 直接 SQL 语法错误 → 500；任意登录用户传
     * {@code ?page=9223372036854775807} 即可触发。上限取 100 万页
     * （× 200 条/页 = 2 亿行），远超本系统任何真实数据量。</p>
     */
    public static final long MAX_PAGE = 1_000_000L;

    /**
     * page 归一化：下限 1、上限 {@value #MAX_PAGE}。
     *
     * <p>此前 7 处各自写 {@code Math.min(Math.max(...))}，且个别端点只做上限未做下限
     * （{@code size<=0} 会生成 {@code LIMIT 0} 或负值）。归一化收敛到此处，新增端点直接复用。</p>
     */
    public static long normalizePage(long page) {
        return Math.min(Math.max(page, 1), MAX_PAGE);
    }

    /** size 归一化：上限 {@value #MAX_SIZE}；缺失/非法（≤0）退回默认 20。 */
    public static long normalizeSize(long size) {
        if (size <= 0) {
            return 20L;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** offset 归一化（与 {@link #normalizePage} 配套）。 */
    public static long offsetOf(long page, long size) {
        return (normalizePage(page) - 1) * normalizeSize(size);
    }

    public static <T> PageResponse<T> of(IPage<T> page) {
        return new PageResponse<>(page.getRecords(), page.getCurrent(), page.getSize(),
                page.getTotal(), page.getPages());
    }

    public static <T> PageResponse<T> of(List<T> list, long page, long size, long total) {
        long totalPages = size <= 0 ? 0 : (total + size - 1) / size;
        return new PageResponse<>(list, page, size, total, totalPages);
    }

    public static <E, T> PageResponse<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getRecords().stream().map(mapper).toList(),
                page.getCurrent(), page.getSize(), page.getTotal(), page.getPages());
    }
}
