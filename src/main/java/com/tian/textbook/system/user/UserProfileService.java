package com.tian.textbook.system.user;

import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户学期归属查询（W6：真源 user_semester_profile）。
 *
 * <p>供 /api/me 等界面聚合使用，使 Controller 不直连 Mapper（SPEC §2 分层约束）。</p>
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserSemesterProfileMapper profileMapper;
    private final CollegeMapper collegeMapper;
    private final SchoolClassMapper classMapper;

    /** 用户在指定学期的归属（学院/班级 + 名称）；无 profile 返回 null。 */
    @Transactional(readOnly = true)
    public SemesterBelonging belongingOf(Long userId, Long semesterId) {
        if (userId == null || semesterId == null) {
            return null;
        }
        UserSemesterProfile profile = profileMapper.selectByUserAndSemester(userId, semesterId);
        if (profile == null) {
            return null;
        }
        String collegeName = null;
        if (profile.getCollegeId() != null) {
            College college = collegeMapper.selectByIdSoft(profile.getCollegeId());
            collegeName = college == null ? null : college.getName();
        }
        String className = null;
        if (profile.getClassId() != null) {
            SchoolClass clazz = classMapper.selectByIdSoft(profile.getClassId());
            className = clazz == null ? null : clazz.getName();
        }
        return new SemesterBelonging(profile.getCollegeId(), collegeName,
                profile.getClassId(), className);
    }

    /** 学期归属快照（学院/班级 id + 名称，名称缺失为 null）。 */
    public record SemesterBelonging(Long collegeId, String collegeName, Long classId, String className) {
    }
}
