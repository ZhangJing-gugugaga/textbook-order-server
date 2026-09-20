package com.tian.textbook.textbook.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.textbook.dto.TextbookSaveRequest;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 教材库维护（SPEC §11.3：/api/admin/textbook/**）。
 *
 * <p>教材跨学期共用（B5），不挂学期域；软删除口径 W9（deleted=0 过滤 + 删除写时间戳），
 * ISBN 唯一键 uk_textbook_isbn(isbn, deleted) 由查重 + DB 双重保证。</p>
 */
@Service
@RequiredArgsConstructor
public class TextbookService {

    private final TextbookMapper textbookMapper;

    /** 教材分页检索（isbn 前缀匹配，title/author/press 模糊匹配） */
    @Transactional(readOnly = true)
    public PageResponse<Textbook> page(String isbn, String title, String author, String press,
                                      Integer status, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 200);
        long offset = (safePage - 1) * safeSize;
        List<Textbook> list = textbookMapper.selectPageByFilter(trimToNull(isbn), trimToNull(title),
                trimToNull(author), trimToNull(press), status, offset, safeSize);
        long total = textbookMapper.countByFilter(trimToNull(isbn), trimToNull(title),
                trimToNull(author), trimToNull(press), status);
        return PageResponse.of(list, safePage, safeSize, total);
    }

    @Transactional
    public Textbook create(TextbookSaveRequest request) {
        String isbn = request.isbn().trim();
        if (textbookMapper.selectByIsbn(isbn) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "教材 ISBN 已存在");
        }
        Textbook textbook = new Textbook();
        applySaveFields(textbook, request);
        textbook.setStatus(request.status() == null ? 1 : validateStatus(request.status()));
        textbook.setDeleted(0L);
        textbookMapper.insert(textbook);
        return textbook;
    }

    @Transactional
    public Textbook update(Long id, TextbookSaveRequest request) {
        Textbook textbook = requireTextbook(id);
        String isbn = request.isbn().trim();
        Textbook dup = textbookMapper.selectByIsbn(isbn);
        if (dup != null && !dup.getId().equals(id)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "教材 ISBN 已存在");
        }
        applySaveFields(textbook, request);
        if (request.status() != null) {
            textbook.setStatus(validateStatus(request.status()));
        }
        textbookMapper.updateById(textbook);
        return textbook;
    }

    /** 停用/启用（1 在库 0 停用） */
    @Transactional
    public Textbook updateStatus(Long id, int status) {
        requireTextbook(id);
        validateStatus(status);
        int rows = textbookMapper.update(null, Wrappers.<Textbook>lambdaUpdate()
                .eq(Textbook::getId, id)
                .eq(Textbook::getDeleted, 0)
                .set(Textbook::getStatus, status));
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "教材不存在");
        }
        return textbookMapper.selectByIdSoft(id);
    }

    public Textbook requireTextbook(Long id) {
        Textbook textbook = textbookMapper.selectByIdSoft(id);
        if (textbook == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教材不存在");
        }
        return textbook;
    }

    private void applySaveFields(Textbook textbook, TextbookSaveRequest request) {
        textbook.setIsbn(request.isbn().trim());
        textbook.setTitle(request.title().trim());
        textbook.setEdition(trimToNull(request.edition()));
        textbook.setAuthor(trimToNull(request.author()));
        textbook.setPress(trimToNull(request.press()));
        textbook.setPrice(request.price());
    }

    private int validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能是 0（停用）或 1（在库）");
        }
        return status;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
