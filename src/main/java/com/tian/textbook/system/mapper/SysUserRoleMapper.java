package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId} AND deleted = 0")
    List<Long> selectRoleIdsByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM sys_user_role WHERE user_id = #{userId} AND role_id = #{roleId} AND deleted = 0")
    SysUserRole selectByUserAndRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /** 绑定该角色的账号数（删除角色前的占用校验，BE-2） */
    @Select("SELECT COUNT(DISTINCT user_id) FROM sys_user_role WHERE role_id = #{roleId} AND deleted = 0")
    long countUsersByRole(@Param("roleId") Long roleId);

    /** 清空某账号的全部角色绑定（角色全量覆盖用；逻辑删除 = deleted 置当前毫秒） */
    @Update("UPDATE sys_user_role SET deleted = #{now} WHERE user_id = #{userId} AND deleted = 0")
    int softDeleteByUser(@Param("userId") Long userId, @Param("now") Long now);
}
