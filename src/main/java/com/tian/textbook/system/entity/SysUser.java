package com.tian.textbook.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号（sys_user）。college_id/class_id 为「当前 active 学期」冗余列，真源见 user_semester_profile（W6）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学号/工号，登录名 */
    private String userNo;

    private String name;

    /** BCrypt */
    private String passwordHash;

    /** 首登校验用（后 4 位比对） */
    private String phone;

    private Long collegeId;

    private Long classId;

    /** 仅订阅消息推送用，不作认证 */
    private String openid;

    /** 1 正常 0 停用 */
    private Integer status;

    /** 初始密码首登后强制改密 */
    private Integer mustChangePassword;

    /** 首登校验（手机号后 4 位/openid）是否通过 */
    private Integer firstLoginVerified;

    /** 登录失败计数（落库，W20） */
    private Integer failCount;

    /** 锁定截止时间 */
    private LocalDateTime lockUntil;

    /** 角色版本号，变更即失效旧 token */
    private Integer roleVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    /** 0=未删；删除时写当前时间戳（W9） */
    private Long deleted;
}
