package com.tian.textbook.textbook.service;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.textbook.dto.CourseSaveRequest;
import com.tian.textbook.textbook.dto.CourseUpdateRequest;
import com.tian.textbook.textbook.entity.Course;
import com.tian.textbook.textbook.mapper.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 课程维护（SPEC §11.3：/api/admin/course/**，course 为学期域数据）。
 *
 * <p>学期归属不可改：新建时指定（缺省 active 学期），编辑仅改 code/name；
 * 唯一键 uk_course(semester_id, code, deleted)，code 非空时同学期查重。</p>
 */
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseMapper courseMapper;
    private final SemesterMapper semesterMapper;

    /** 课程列表（semesterId 缺省 = 当前 active 学期） */
    @Transactional(readOnly = true)
    public List<Course> list(Long semesterId) {
        return courseMapper.selectBySemester(resolveSemesterId(semesterId));
    }

    @Transactional
    public Course create(CourseSaveRequest request) {
        Long semesterId = resolveSemesterId(request.semesterId());
        requireSemester(semesterId);
        String code = trimToNull(request.code());
        if (code != null && courseMapper.selectBySemesterAndCode(semesterId, code) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "课程代码已存在");
        }
        Course course = new Course();
        course.setSemesterId(semesterId);
        course.setCode(code);
        course.setName(request.name().trim());
        course.setDeleted(0L);
        courseMapper.insert(course);
        return course;
    }

    @Transactional
    public Course update(Long id, CourseUpdateRequest request) {
        Course course = requireCourse(id);
        if (request.code() != null) {
            String code = trimToNull(request.code());
            if (code != null && !code.equals(course.getCode())) {
                Course dup = courseMapper.selectBySemesterAndCode(course.getSemesterId(), code);
                if (dup != null && !dup.getId().equals(id)) {
                    throw new BizException(ErrorCode.STATE_CONFLICT, "课程代码已存在");
                }
            }
            course.setCode(code);
        }
        course.setName(request.name().trim());
        courseMapper.updateById(course);
        return course;
    }

    public Course requireCourse(Long id) {
        Course course = courseMapper.selectByIdSoft(id);
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        return course;
    }

    /** 请求未带学期时取 active 学期快照（SPEC §5.4）；无激活学期不允许默认落库 */
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

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
