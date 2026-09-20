package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.SysUserToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserTokenMapper extends BaseMapper<SysUserToken> {

    @Select("SELECT * FROM sys_user_token WHERE token_hash = #{tokenHash} AND deleted = 0")
    SysUserToken selectByHash(@Param("tokenHash") String tokenHash);

    @Update("UPDATE sys_user_token SET revoked = 1, updated_at = NOW(3) WHERE id = #{id} AND revoked = 0")
    int revokeById(@Param("id") Long id);

    @Update("UPDATE sys_user_token SET revoked = 1, updated_at = NOW(3) WHERE user_id = #{userId} AND revoked = 0 AND deleted = 0")
    int revokeAllByUser(@Param("userId") Long userId);

    /** 清理过期令牌（保留 7 天以上历史的删除由运维负责） */
    @Update("UPDATE sys_user_token SET deleted = #{now} WHERE user_id = #{userId} AND revoked = 1 AND expire_at < #{before}")
    int softDeleteExpiredRevoked(@Param("userId") Long userId, @Param("before") java.time.LocalDateTime before, @Param("now") Long now);
}
