package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId} AND deleted = 0")
    List<Long> selectRoleIdsByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM sys_user_role WHERE user_id = #{userId} AND role_id = #{roleId} AND deleted = 0")
    SysUserRole selectByUserAndRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
