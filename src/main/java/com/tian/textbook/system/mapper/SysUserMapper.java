package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE user_no = #{userNo} AND deleted = 0")
    SysUser selectByUserNo(@Param("userNo") String userNo);

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0")
    SysUser selectByIdSoft(@Param("id") Long id);

    /** 登录失败计数 +1（落库，W20） */
    @Update("UPDATE sys_user SET fail_count = fail_count + 1, updated_at = NOW(3) WHERE id = #{id}")
    int incrFailCount(@Param("id") Long id);

    @Update("UPDATE sys_user SET fail_count = 0, lock_until = #{lockUntil}, updated_at = NOW(3) WHERE id = #{id}")
    int resetFailCountAndLock(@Param("id") Long id, @Param("lockUntil") java.time.LocalDateTime lockUntil);

    @Update("UPDATE sys_user SET fail_count = 0, lock_until = NULL, updated_at = NOW(3) WHERE id = #{id}")
    int clearFailState(@Param("id") Long id);

    @Update("UPDATE sys_user SET role_version = role_version + 1, updated_at = NOW(3) WHERE id = #{id}")
    int incrRoleVersion(@Param("id") Long id);

    /** 撤销该用户全部 refresh（登出/停用/改密/角色变更，W21） */
    @Update("UPDATE sys_user_token SET revoked = 1, updated_at = NOW(3) WHERE user_id = #{userId} AND revoked = 0 AND deleted = 0")
    int revokeAllTokens(@Param("userId") Long userId);

    /** 按学院集合查用户（导入停用比对/账号检索） */
    @Select("<script>SELECT * FROM sys_user WHERE deleted = 0 AND status = 1 AND college_id IN "
            + "<foreach item='c' collection='collegeIds' open='(' separator=',' close=')'>#{c}</foreach></script>")
    List<SysUser> selectActiveByCollegeIds(@Param("collegeIds") List<Long> collegeIds);
}
