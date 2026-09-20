package com.tian.textbook.common.semester;

/**
 * 学期上下文（SPEC §5.4：请求进入时把 activeSemesterId 写入 ThreadLocal，全程使用该快照，
 * 切换瞬间在途请求按旧学期快照完成，不串学期）。
 *
 * <p>由 SemesterContextInterceptor 在请求前设置、请求结束清理；定时任务自行 set/clear。</p>
 */
public final class SemesterContextHolder {

    private static final ThreadLocal<Long> ACTIVE_SEMESTER = new ThreadLocal<>();

    private SemesterContextHolder() {
    }

    public static void set(Long semesterId) {
        ACTIVE_SEMESTER.set(semesterId);
    }

    /** 当前 active 学期 id；无激活学期时返回 null（业务侧按需报错）。 */
    public static Long get() {
        return ACTIVE_SEMESTER.get();
    }

    public static Long require() {
        Long id = ACTIVE_SEMESTER.get();
        if (id == null) {
            throw new IllegalStateException("active semester not resolved in context");
        }
        return id;
    }

    public static void clear() {
        ACTIVE_SEMESTER.remove();
    }
}
