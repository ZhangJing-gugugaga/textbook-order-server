package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    /**
     * 清空某角色的全部授权（角色-权限全量覆盖 / 删除角色的级联，BE-2）。
     *
     * <p>逻辑删除：{@code deleted} 置当前毫秒。唯一键是 {@code (role_id, perm_id, deleted)}，
     * 置非 0 后同一组合可重新插入，不会撞键。</p>
     */
    @Update("UPDATE sys_role_permission SET deleted = #{now} WHERE role_id = #{roleId} AND deleted = 0")
    int softDeleteByRole(@Param("roleId") Long roleId, @Param("now") Long now);
}
