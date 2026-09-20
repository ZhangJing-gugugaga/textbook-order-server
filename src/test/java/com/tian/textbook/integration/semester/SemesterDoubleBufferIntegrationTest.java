package com.tian.textbook.integration.semester;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.semester.SemesterService;
import com.tian.textbook.semester.dto.SemesterActivateRequest;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.support.IntegrationTestBase;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双缓冲原子切换集成测试（SPEC §7 / W1/W6，H2 承载）。
 *
 * <p>覆盖：draft→active 单事务切换（旧学期 archived、同刻仅一个 active、sys_user 归属列由
 * user_semester_profile 同步）、version 不匹配 409 且回滚、重复激活非 draft 409。</p>
 */
class SemesterDoubleBufferIntegrationTest extends IntegrationTestBase {

    @Autowired
    private SemesterService semesterService;
    @Autowired
    private SemesterMapper semesterMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private TestDataSeeder seeder;

    @AfterEach
    void tearDown() {
        SemesterContextHolder.clear();
        TestSecurity.clear();
    }

    private List<Semester> activeSemesters() {
        return semesterMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<Semester>lambdaQuery().eq(Semester::getActiveStatus, "active")
                .eq(Semester::getDeleted, 0));
    }

    @Test
    @DisplayName("activate：旧 active 归档、新 draft 激活、同刻仅一个 active、归属列由 profile 同步")
    void activate_switchesActiveSemesterAtomically() {
        // 旧 active 学期（在册学生归属学院A）
        Semester oldActive = seeder.semester("2025-2026-1", LocalDate.of(2025, 9, 1),
                LocalDate.of(2026, 1, 15), null, null, 1, 1);
        semesterMapper.activateIfDraft(oldActive.getId(), oldActive.getVersion());

        var college = seeder.college("计算机学院");
        var major = seeder.major(college.getId(), "软件工程");
        var clazz = seeder.schoolClass(major.getId(), "软工2401", 50);

        // 新 draft 学期 + profile（新学期归属真源）
        Semester draft = seeder.semester("2026-2027-1", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 15), null, null, 1, 1);
        SysUser enrolled = seeder.user("2024001", "张三", "13800000001",
                null, null, 1, 0, 1, "STUDENT");
        SysUser notEnrolled = seeder.user("2024002", "李四", "13800000002",
                null, null, 1, 0, 1, "STUDENT");
        seeder.profile(enrolled.getId(), draft.getId(), college.getId(), clazz.getId());
        seeder.profile(notEnrolled.getId(), oldActive.getId(), college.getId(), clazz.getId());

        // 双缓冲切换（version 匹配）
        Semester activated = semesterService.activate(draft.getId(),
                new SemesterActivateRequest(draft.getVersion()));

        assertThat(activated.getActiveStatus()).isEqualTo("active");
        assertThat(semesterMapper.selectByIdSoft(oldActive.getId()).getActiveStatus())
                .isEqualTo("archived");
        assertThat(semesterMapper.selectByIdSoft(draft.getId()).getActiveStatus())
                .isEqualTo("active");
        // 同刻仅一个 active（DB 层 uk_semester_active 兜底 + 查询断言）
        assertThat(activeSemesters()).singleElement()
                .satisfies(s -> assertThat(s.getId()).isEqualTo(draft.getId()));

        // sys_user 归属列由新学期 profile 同步：在册 → 取 profile；无 profile → 置空
        SysUser reloaded = userMapper.selectByIdSoft(enrolled.getId());
        assertThat(reloaded.getCollegeId()).isEqualTo(college.getId());
        assertThat(reloaded.getClassId()).isEqualTo(clazz.getId());
        SysUser dropped = userMapper.selectByIdSoft(notEnrolled.getId());
        assertThat(dropped.getCollegeId()).isNull();
        assertThat(dropped.getClassId()).isNull();
    }

    @Test
    @DisplayName("activate：version 不匹配 → 409 STATE_CONFLICT 且状态不变（回滚）")
    void activate_versionMismatch_returns409AndKeepsState() {
        Semester oldActive = seeder.semester("2025-2026-2", LocalDate.of(2025, 9, 1),
                LocalDate.of(2026, 1, 15), null, null, 1, 1);
        semesterMapper.activateIfDraft(oldActive.getId(), oldActive.getVersion());
        Semester draft = seeder.semester("2026-2027-2", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 15), null, null, 1, 1);

        assertThatThrownBy(() -> semesterService.activate(draft.getId(),
                new SemesterActivateRequest(draft.getVersion() + 99)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));

        // 回滚：目标仍是 draft、旧学期仍 active
        assertThat(semesterMapper.selectByIdSoft(draft.getId()).getActiveStatus()).isEqualTo("draft");
        assertThat(semesterMapper.selectByIdSoft(oldActive.getId()).getActiveStatus())
                .isEqualTo("active");
        assertThat(activeSemesters()).singleElement()
                .satisfies(s -> assertThat(s.getId()).isEqualTo(oldActive.getId()));
    }

    @Test
    @DisplayName("activate：重复激活非 draft 学期 → 409 STATE_CONFLICT")
    void activate_nonDraftSemester_returns409() {
        Semester active = seeder.semester("2025-2026-3", LocalDate.of(2025, 9, 1),
                LocalDate.of(2026, 1, 15), null, null, 1, 1);
        semesterMapper.activateIfDraft(active.getId(), active.getVersion());

        assertThatThrownBy(() -> semesterService.activate(active.getId(),
                new SemesterActivateRequest(active.getVersion())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));

        assertThat(activeSemesters()).singleElement()
                .satisfies(s -> assertThat(s.getId()).isEqualTo(active.getId()));
    }

    @Test
    @DisplayName("activate：库中 version 已被并发修改 → 409 STATE_CONFLICT 且无 active 学期产生")
    void activate_staleVersionInDb_returns409() {
        Semester draft = seeder.semester("2026-2027-3", LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 15), null, null, 1, 1);
        // 无旧 active；人为让 activateIfDraft 匹配不上（version 在库已被改）
        semesterMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<Semester>lambdaUpdate().eq(Semester::getId, draft.getId())
                .set(Semester::getVersion, draft.getVersion() + 1));

        assertThatThrownBy(() -> semesterService.activate(draft.getId(),
                new SemesterActivateRequest(draft.getVersion())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));
        assertThat(activeSemesters()).isEmpty();
    }
}
