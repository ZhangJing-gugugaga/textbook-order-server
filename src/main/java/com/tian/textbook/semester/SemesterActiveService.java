package com.tian.textbook.semester;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * active 学期读取（短 TTL 只读缓存；双缓冲切换后立即失效，保证新请求读到新 active 学期）。
 */
@Service
@RequiredArgsConstructor
public class SemesterActiveService {

    private static final String KEY = "active";

    private final SemesterMapper semesterMapper;

    private final Cache<String, Semester> cache = Caffeine.newBuilder()
            .expireAfterWrite(3, TimeUnit.SECONDS)
            .maximumSize(1)
            .build();

    /** 当前 active 学期；无激活学期返回 null（不缓存 null，每次都查） */
    public Semester active() {
        return cache.get(KEY, k -> semesterMapper.selectActive());
    }

    public Long activeId() {
        Semester active = active();
        return active == null ? null : active.getId();
    }

    public void evict() {
        cache.invalidateAll();
    }
}
