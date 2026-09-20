package com.tian.textbook.system.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号列表项（超管账号检索）。
 */
@Data
public class UserListItem {

    private Long id;

    private String userNo;

    private String name;

    private String phone;

    private Long collegeId;

    private String collegeName;

    private Long classId;

    private String className;

    /** 1 正常 0 停用 */
    private Integer status;

    private Integer mustChangePassword;

    private Integer firstLoginVerified;

    private Boolean openidBound;

    private LocalDateTime lockUntil;

    private List<String> roles;

    private LocalDateTime createdAt;
}
