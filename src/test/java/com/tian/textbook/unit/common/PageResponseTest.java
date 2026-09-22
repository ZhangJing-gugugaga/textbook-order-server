package com.tian.textbook.unit.common;

import com.tian.textbook.common.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页参数归一化单元测试（R5）。
 *
 * <p>缺陷背景：{@code normalizePage} 只做下限 1，没有上限。{@code offset = (page-1)*size}
 * 在 {@code page = Long.MAX_VALUE} 时溢出为负，生成的 {@code LIMIT n OFFSET -x} 直接
 * SQL 语法错误 → 500，任意登录用户传一个极大 page 即可触发。</p>
 */
class PageResponseTest {

    @Test
    @DisplayName("R5：page 极大时被压到上限，offset 不再溢出为负")
    void normalizePage_capsHugePage() {
        assertThat(PageResponse.normalizePage(Long.MAX_VALUE))
                .isEqualTo(PageResponse.MAX_PAGE);
        assertThat(PageResponse.offsetOf(Long.MAX_VALUE, 200))
                .as("offset 必须非负（负值会让 SQL 语法错误 → 500）")
                .isNotNegative();
    }

    @Test
    @DisplayName("page 下限 1、size 下限与上限")
    void normalize_boundsPageAndSize() {
        assertThat(PageResponse.normalizePage(0)).isEqualTo(1);
        assertThat(PageResponse.normalizePage(-5)).isEqualTo(1);
        assertThat(PageResponse.normalizePage(3)).isEqualTo(3);

        assertThat(PageResponse.normalizeSize(0)).isEqualTo(20);
        assertThat(PageResponse.normalizeSize(-1)).isEqualTo(20);
        assertThat(PageResponse.normalizeSize(50)).isEqualTo(50);
        assertThat(PageResponse.normalizeSize(99999)).isEqualTo(PageResponse.MAX_SIZE);
    }

    @Test
    @DisplayName("offsetOf 与归一化后的 page/size 一致")
    void offsetOf_matchesNormalizedValues() {
        assertThat(PageResponse.offsetOf(1, 20)).isZero();
        assertThat(PageResponse.offsetOf(3, 20)).isEqualTo(40);
        assertThat(PageResponse.offsetOf(Long.MAX_VALUE, Long.MAX_VALUE))
                .isEqualTo((PageResponse.MAX_PAGE - 1) * PageResponse.MAX_SIZE);
    }

    @Test
    @DisplayName("PageResponse.of 的 totalPages 计算（size<=0 时为 0）")
    void of_computesTotalPages() {
        assertThat(PageResponse.of(List.of(), 1, 20, 0).totalPages()).isZero();
        assertThat(PageResponse.of(List.of(), 1, 20, 21).totalPages()).isEqualTo(2);
        assertThat(PageResponse.of(List.of(), 1, 0, 21).totalPages()).isZero();
    }
}
