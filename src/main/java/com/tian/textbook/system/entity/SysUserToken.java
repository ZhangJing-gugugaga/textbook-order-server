package com.tian.textbook.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * refresh 令牌（sys_user_token，W21）：随机串的 SHA-256 落库，轮换 = 旧行 revoked=1 发新行。
 */
@Data
@TableName("sys_user_token")
public class SysUserToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** refresh token 的 SHA-256 */
    private String tokenHash;

    private LocalDateTime expireAt;

    /** 0 有效 1 已撤销 */
    private Integer revoked;

    private String deviceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
