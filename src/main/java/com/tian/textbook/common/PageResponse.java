package com.tian.textbook.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应（SPEC §11：分页参数 page/size 默认 1/20，上限 200；page 从 1 开始）。
 */
public record PageResponse<T>(List<T> list, long page, long size, long total, long totalPages) {

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
