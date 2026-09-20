package com.tian.textbook.system.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.Major;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.MajorMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 组织三表维护（学院/专业/行政班，SPEC §11.3）。
 *
 * <p>严格模式：导入名单时不自动创建学院/班级（模块 4），此处为唯一维护入口。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgService {

    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final SchoolClassMapper classMapper;

    // ============ 学院 ============

    @Transactional(readOnly = true)
    public List<College> listColleges() {
        return collegeMapper.selectList(Wrappers.<College>lambdaQuery()
                .eq(College::getDeleted, 0).orderByAsc(College::getId));
    }

    @Transactional
    public College createCollege(String name, String fullName) {
        if (collegeMapper.selectByName(name.trim()) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "学院名称已存在");
        }
        College college = new College();
        college.setName(name.trim());
        college.setFullName(fullName);
        college.setDeleted(0L);
        collegeMapper.insert(college);
        return college;
    }

    @Transactional
    public College updateCollege(Long id, String name, String fullName) {
        College college = requireCollege(id);
        if (name != null && !name.isBlank()) {
            college.setName(name.trim());
        }
        if (fullName != null) {
            college.setFullName(fullName);
        }
        collegeMapper.updateById(college);
        return college;
    }

    // ============ 专业 ============

    @Transactional(readOnly = true)
    public List<Major> listMajors(Long collegeId) {
        return majorMapper.selectList(Wrappers.<Major>lambdaQuery()
                .eq(Major::getDeleted, 0)
                .eq(collegeId != null, Major::getCollegeId, collegeId)
                .orderByAsc(Major::getId));
    }

    @Transactional
    public Major createMajor(Long collegeId, String name, String fullName) {
        requireCollege(collegeId);
        if (majorMapper.selectByCollegeAndName(collegeId, name.trim()) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "专业名称已存在");
        }
        Major major = new Major();
        major.setCollegeId(collegeId);
        major.setName(name.trim());
        major.setFullName(fullName);
        major.setDeleted(0L);
        majorMapper.insert(major);
        return major;
    }

    @Transactional
    public Major updateMajor(Long id, String name, String fullName) {
        Major major = requireMajor(id);
        if (name != null && !name.isBlank()) {
            major.setName(name.trim());
        }
        if (fullName != null) {
            major.setFullName(fullName);
        }
        majorMapper.updateById(major);
        return major;
    }

    // ============ 行政班 ============

    @Transactional(readOnly = true)
    public List<SchoolClass> listClasses(Long majorId) {
        return classMapper.selectList(Wrappers.<SchoolClass>lambdaQuery()
                .eq(SchoolClass::getDeleted, 0)
                .eq(majorId != null, SchoolClass::getMajorId, majorId)
                .orderByAsc(SchoolClass::getId));
    }

    @Transactional
    public SchoolClass createClass(Long majorId, String name, String grade, Integer studentCount) {
        requireMajor(majorId);
        if (classMapper.selectByMajorAndName(majorId, name.trim()) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "班级名称已存在");
        }
        SchoolClass clazz = new SchoolClass();
        clazz.setMajorId(majorId);
        clazz.setName(name.trim());
        clazz.setGrade(grade);
        clazz.setStudentCount(studentCount == null ? 0 : studentCount);
        clazz.setDeleted(0L);
        classMapper.insert(clazz);
        return clazz;
    }

    @Transactional
    public SchoolClass updateClass(Long id, String name, String grade, Integer studentCount) {
        SchoolClass clazz = requireClass(id);
        if (name != null && !name.isBlank()) {
            clazz.setName(name.trim());
        }
        if (grade != null) {
            clazz.setGrade(grade);
        }
        if (studentCount != null) {
            clazz.setStudentCount(studentCount);
        }
        classMapper.updateById(clazz);
        return clazz;
    }

    public College requireCollege(Long id) {
        College college = collegeMapper.selectByIdSoft(id);
        if (college == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学院不存在");
        }
        return college;
    }

    public Major requireMajor(Long id) {
        Major major = majorMapper.selectByIdSoft(id);
        if (major == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专业不存在");
        }
        return major;
    }

    public SchoolClass requireClass(Long id) {
        SchoolClass clazz = classMapper.selectByIdSoft(id);
        if (clazz == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
        }
        return clazz;
    }
}
