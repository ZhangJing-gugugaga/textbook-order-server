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

    /**
     * 账号检索分页（角色/学院/状态/关键字）。详见 resources/mapper/system/SysUserMapper.xml。
     */
    List<com.tian.textbook.system.dto.UserListItem> selectPageByFilter(
            @Param("roleCode") String roleCode,
            @Param("collegeId") Long collegeId,
            @Param("status") Integer status,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("limit") long limit);

    long countByFilter(@Param("roleCode") String roleCode,
                       @Param("collegeId") Long collegeId,
                       @Param("status") Integer status,
                       @Param("keyword") String keyword);

    /** 双缓冲切换⑤：由 user_semester_profile 同步 sys_user 归属冗余列（W6） */
    /**
     * 双缓冲切换⑤：由 user_semester_profile 同步 sys_user 归属冗余列（W6）。
     *
     * <p>语义等价于 MySQL 多表 UPDATE（{@code UPDATE sys_user u LEFT JOIN user_semester_profile p ... SET u.college_id = p.college_id}）：
     * 新学期有 profile 的用户取其归属，无 profile 的用户置 NULL。改用相关子查询表达，
     * MySQL 与 H2（MySQL 模式，集成测试用）均可执行；子查询只读 user_semester_profile，
     * 不触发 MySQL「can't specify target table」限制。</p>
     */
    @Update("UPDATE sys_user SET college_id = (SELECT p.college_id FROM user_semester_profile p "
            + "WHERE p.user_id = sys_user.id AND p.semester_id = #{semesterId} AND p.deleted = 0), "
            + "class_id = (SELECT p.class_id FROM user_semester_profile p "
            + "WHERE p.user_id = sys_user.id AND p.semester_id = #{semesterId} AND p.deleted = 0), "
            + "updated_at = NOW(3)")
    int syncCollegeClassFromProfile(@Param("semesterId") Long semesterId);

    /** 停用：即时踢下线（role_version+1 使旧 access 失效） */
    @Update("UPDATE sys_user SET status = 0, updated_at = NOW(3) WHERE id = #{id} AND deleted = 0")
    int disableById(@Param("id") Long id);

    @Update("UPDATE sys_user SET status = 1, updated_at = NOW(3) WHERE id = #{id} AND deleted = 0")
    int enableById(@Param("id") Long id);
}
