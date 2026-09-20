package com.tian.textbook.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 专业（major，组织三表之二）。
 */
@Data
@TableName("major")
public class Major {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long collegeId;

    private String name;

    private String fullName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
