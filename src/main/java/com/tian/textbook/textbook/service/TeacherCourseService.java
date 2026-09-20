package com.tian.textbook.textbook.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import com.tian.textbook.textbook.dto.TeacherCourseListItem;
import com.tian.textbook.textbook.dto.TeacherCourseSaveRequest;
import com.tian.textbook.textbook.entity.Course;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.mapper.CourseMapper;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TeacherCourseQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任课关系维护（SPEC §11.3：/api/admin/teacher-course/**，teacher_course 为学期域数据）。
 *
 * <p>征订范围即本表（W17）；唯一键 uk_tc(semester_id, teacher_id, course_id, class_id, deleted)
 * 由 selectExact 查重 + DB 双重保证；删除 = 逻辑删除（deleted 写当前毫秒，W9）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeacherCourseService {

    private static final String ROLE_CODE_TEACHER = "TEACHER";

    private final TeacherCourseMapper teacherCourseMapper;
    private final TeacherCourseQueryMapper teacherCourseQueryMapper;
    private final CourseMapper courseMapper;
    private final SchoolClassMapper classMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SemesterMapper semesterMapper;

    /** 任课关系列表（含课程名/教师名/班级名；semesterId 缺省 = active 学期） */
    @Transactional(readOnly = true)
    public List<TeacherCourseListItem> list(Long semesterId, Long teacherId, Long classId) {
        return teacherCourseQueryMapper.selectWithNames(resolveSemesterId(semesterId), teacherId, classId);
    }

    @Transactional
    public TeacherCourse create(TeacherCourseSaveRequest request) {
        Long semesterId = resolveSemesterId(request.semesterId());
        requireSemester(semesterId);
        requireTeacher(request.teacherId());
        requireCourse(request.courseId());
        requireClass(request.classId());
        if (teacherCourseMapper.selectExact(semesterId, request.teacherId(),
                request.courseId(), request.classId()) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任课关系已存在");
        }
        TeacherCourse teacherCourse = new TeacherCourse();
        teacherCourse.setSemesterId(semesterId);
        teacherCourse.setTeacherId(request.teacherId());
        teacherCourse.setCourseId(request.courseId());
        teacherCourse.setClassId(request.classId());
        teacherCourse.setDeleted(0L);
        teacherCourseMapper.insert(teacherCourse);
        return teacherCourse;
    }

    /** 逻辑删除（UPDATE ... SET deleted = 当前毫秒 WHERE id = ? AND deleted = 0） */
    @Transactional
    public void delete(Long id) {
        int rows = teacherCourseMapper.update(null, Wrappers.<TeacherCourse>lambdaUpdate()
                .eq(TeacherCourse::getId, id)
                .eq(TeacherCourse::getDeleted, 0)
                .set(TeacherCourse::getDeleted, System.currentTimeMillis()));
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "任课关系不存在");
        }
    }

    /** 教师必须存在且带 TEACHER 角色（sys_user_role → sys_role，W10 多角色取并集之外的任课准入） */
    private void requireTeacher(Long teacherId) {
        SysUser teacher = userMapper.selectByIdSoft(teacherId);
        if (teacher == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师不存在");
        }
        SysRole teacherRole = roleMapper.selectByCode(ROLE_CODE_TEACHER);
        if (teacherRole == null || !userRoleMapper.selectRoleIdsByUser(teacherId).contains(teacherRole.getId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该用户未分配教师角色");
        }
    }

    private void requireCourse(Long courseId) {
        Course course = courseMapper.selectByIdSoft(courseId);
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
    }

    private void requireClass(Long classId) {
        SchoolClass clazz = classMapper.selectByIdSoft(classId);
        if (clazz == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
        }
    }

    /** 请求未带学期时取 active 学期快照（SPEC §5.4） */
    private Long resolveSemesterId(Long requested) {
        if (requested != null) {
            return requested;
        }
        Long active = SemesterContextHolder.get();
        if (active == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前没有激活学期，请指定学期");
        }
        return active;
    }

    private void requireSemester(Long semesterId) {
        Semester semester = semesterMapper.selectByIdSoft(semesterId);
        if (semester == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学期不存在");
        }
    }
}
