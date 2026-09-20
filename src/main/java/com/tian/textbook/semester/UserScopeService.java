package com.tian.textbook.semester;

import com.tian.textbook.common.datascope.CollegeResolver;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户学院归属解析（真源 user_semester_profile，W6）。
 *
 * <p>实现 common 的 CollegeResolver，供数据隔离切面使用；短 TTL 缓存加速。</p>
 */
@Service
@RequiredArgsConstructor
public class UserScopeService implements CollegeResolver {

    private final UserSemesterProfileMapper profileMapper;

    @Override
    public Long collegeIdOf(Long userId, Long semesterId) {
        if (userId == null || semesterId == null) {
            return null;
        }
        return profileMapper.selectCollegeId(userId, semesterId);
    }
}
