package com.tian.textbook.semester;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

/**
 * active 学期读取（短 TTL 只读缓存；双缓冲切换后立即失效，保证新请求读到新 active 学期）。
 *
 * <p>{@link #evict()} 在事务内被调用时，实际失效动作推迟到<b>事务完成后</b>执行：
 * 若在事务内立即失效，并发请求会未命中缓存并读到库中<b>尚未提交</b>的旧 active 学期，
 * 随后把旧值回填缓存，形成约 3 秒（TTL）的窗口——期间已归档学期的写入仍被放行。</p>
 */
@Slf4j
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

    /**
     * 失效缓存。有事务上下文时注册 afterCompletion 回调（避免用未提交的旧值回填缓存）；
     * 无事务上下文时立即失效。
     */
    public void evict() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    invalidate();
                }
            });
            return;
        }
        invalidate();
    }

    private void invalidate() {
        cache.invalidateAll();
        log.debug("active 学期缓存已失效");
    }
}
