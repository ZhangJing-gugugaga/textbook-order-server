package com.tian.textbook.textbook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 教材库（textbook，跨学期共用，B5）。
 */
@Data
@TableName("textbook")
public class Textbook {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String isbn;

    private String title;

    private String edition;

    private String author;

    private String press;

    private BigDecimal price;

    /** 1 在库 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
