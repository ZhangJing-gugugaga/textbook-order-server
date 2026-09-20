package com.tian.textbook.support;

import com.tian.textbook.semester.SemesterService;
import com.tian.textbook.semester.mapper.SemesterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 征订业务场景工厂（集成测试共用：学院/专业/班级/教师/学生/教材/课程/任课/active 学期）。
 *
 * <p>把「造数」集中到一个 Builder，避免各集成测试散落魔法值；默认造 50 人班级
 * （QTY_RANGE 上限来源，W2）、在库教材（合法 ISBN-13）、已激活学期。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderScenarioFactory {

    public record Scenario(Long semesterId, Long collegeId, Long majorId, Long classId,
                           Long teacherId, Long studentId, Long textbookId, Long courseId,
                           LocalDateTime windowEnd) {
    }

    private final TestDataSeeder seeder;
    private final SemesterMapper semesterMapper;
    private final SemesterService semesterService;

    /** 默认造数：窗口已开启（channel_open=1 + window_status=open）。 */
    public Scenario seed(String suffix) {
        return seed(suffix, true);
    }

    /**
     * @param windowOpen true = 手动开启窗口；false = 保持 not_open（提交将被 409 拦截）
     */
    public Scenario seed(String suffix, boolean windowOpen) {
        LocalDateTime windowStart = LocalDateTime.now().minusDays(1);
        LocalDateTime windowEnd = LocalDateTime.now().plusDays(7);

        var college = seeder.college("计算机学院" + suffix);
        var major = seeder.major(college.getId(), "软件工程" + suffix);
        var clazz = seeder.schoolClass(major.getId(), "软工2401" + suffix, 50);

        var teacher = seeder.user("T" + suffix, "教师" + suffix, "1380000" + suffix,
                college.getId(), null, 1, 0, 1, "TEACHER");
        var student = seeder.user("S" + suffix, "学生" + suffix, "1390000" + suffix,
                college.getId(), clazz.getId(), 1, 0, 1, "STUDENT");

        var semester = seeder.semester("2026-2027-" + suffix, null, null,
                windowStart, windowEnd, 1, 1);
        semesterMapper.activateIfDraft(semester.getId(), semester.getVersion());
        if (windowOpen) {
            semesterService.openWindow(semester.getId());
        }

        seeder.profile(teacher.getId(), semester.getId(), college.getId(), null);
        seeder.profile(student.getId(), semester.getId(), college.getId(), clazz.getId());

        var textbook = seeder.textbook("978-0-306-40615-7", "高等数学" + suffix, 1);
        var course = seeder.course(semester.getId(), "CS" + suffix, "高等数学" + suffix);
        seeder.teacherCourse(semester.getId(), teacher.getId(), course.getId(), clazz.getId());

        return new Scenario(semester.getId(), college.getId(), major.getId(), clazz.getId(),
                teacher.getId(), student.getId(), textbook.getId(), course.getId(), windowEnd);
    }
}
