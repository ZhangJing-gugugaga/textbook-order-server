package com.tian.textbook.semester.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.semester.entity.Semester;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SemesterMapper extends BaseMapper<Semester> {

    @Select("SELECT * FROM semester WHERE active_status = 'active' AND deleted = 0")
    Semester selectActive();

    @Select("SELECT * FROM semester WHERE id = #{id} AND deleted = 0")
    Semester selectByIdSoft(@Param("id") Long id);

    /** 原子切换：仅当仍为 draft 且 version 匹配才置 active（乐观锁，W1） */
    @Update("UPDATE semester SET active_status = 'active', updated_at = NOW(3) "
            + "WHERE id = #{id} AND active_status = 'draft' AND version = #{version}")
    int activateIfDraft(@Param("id") Long id, @Param("version") int version);

    /**
     * 归档当前 active 学期（手动归档 + 切换事务第二步）。
     *
     * <p>同时关闭窗口（{@code channel_open=0, window_status='closed'}）：归档只改 active_status 时，
     * 窗口判定（{@code channel_open=1 && window_status='open'}）会继续放行，已归档学期仍可写入。
     * 窗口状态是窗口判定的唯一真源，必须与归档同步收敛。</p>
     *
     * <p>同时 {@code version+1}：归档改变了学期状态，版本号必须随之前进，否则归档前读到的
     * version 在归档后依然「匹配」，乐观锁形同虚设（撤销归档 {@link #unarchiveIfArchived}
     * 正是用 version 作为并发令牌）。</p>
     */
    @Update("UPDATE semester SET active_status = 'archived', channel_open = 0, window_status = 'closed', "
            + "version = version + 1, updated_at = NOW(3) "
            + "WHERE id = #{id} AND active_status = 'active'")
    int archiveIfActive(@Param("id") Long id);

    /**
     * 撤销归档（受限回滚，B15）：仅 archived 学期且 version 匹配时恢复为 active。
     *
     * <p>窗口不在此处恢复：归档时已被强制关闭（{@code channel_open=0 / window_status='closed'}），
     * 回滚后保持关闭，需管理员显式重新开启——避免一次回滚就把对外填报通道重新打开。</p>
     */
    @Update("UPDATE semester SET active_status = 'active', version = version + 1, updated_at = NOW(3) "
            + "WHERE id = #{id} AND active_status = 'archived' AND version = #{version}")
    int unarchiveIfArchived(@Param("id") Long id, @Param("version") int version);
}
