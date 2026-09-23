package com.tian.textbook.importexport.service;

import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.importexport.dto.ClassSizeDiff;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.Major;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.MajorMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 班级人数下调门禁（B13 局部名单防护）。
 *
 * <p>背景：{@code school_class.student_count} 是教师征订数量的硬上限（W2），学生名单导入按
 * 「文件内该班去重人数」重算它——这是既定设计（名单即权威口径）。但设计缺一个防护：
 * **局部名单**（只放了几行的文件）会把整班上限压到文件行数，线上实测「软工2023-1 50 → 2」，
 * 该班教师随即无法填报（数量上限被卡死），且恢复只能靠管理员手工改班级人数。</p>
 *
 * <p>防护方式：下调幅度同时满足「比例 &gt; {@code textbook.import.class-size-shrink-confirm-pct}%」
 * 与「绝对人数 &ge; {@code ...-min-drop}」时判定为疑似局部名单 → 导入请求必须显式
 * {@code confirmClassSizeShrink=true}，否则 409 并回显逐班 diff。管理员可先调
 * {@code POST /api/admin/user/import/preview} 看 diff 再决定（见 ImportPreviewResponse）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassSizeGuard {

    /** 409 文案里最多列举的班级数（避免一次导入几十个班把 message 撑爆） */
    private static final int MAX_LISTED = 5;

    private final SchoolClassMapper schoolClassMapper;
    private final MajorMapper majorMapper;
    private final CollegeMapper collegeMapper;
    private final TextbookProperties properties;

    /**
     * 计算班级人数 diff（只读）。
     *
     * @param incomingCounts 班级 id → 文件内去重学生数（{@code ImportRunContext#classCounts}）
     * @return 按下调幅度降序排列的 diff 列表（未变化的班级不返回）
     */
    public List<ClassSizeDiff> diff(Map<Long, Integer> incomingCounts) {
        if (incomingCounts == null || incomingCounts.isEmpty()) {
            return List.of();
        }
        int pct = properties.getImportConfig().getClassSizeShrinkConfirmPct();
        int minDrop = properties.getImportConfig().getClassSizeShrinkConfirmMinDrop();
        List<ClassSizeDiff> diffs = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : incomingCounts.entrySet()) {
            Long classId = entry.getKey();
            int incoming = entry.getValue() == null ? 0 : entry.getValue();
            SchoolClass clazz = schoolClassMapper.selectByIdSoft(classId);
            if (clazz == null) {
                continue;
            }
            int current = clazz.getStudentCount() == null ? 0 : clazz.getStudentCount();
            if (current == incoming) {
                continue;
            }
            int drop = Math.max(0, current - incoming);
            int dropPct = current <= 0 ? 0 : (int) Math.round(drop * 100.0 / current);
            diffs.add(new ClassSizeDiff(classId, clazz.getName(),
                    nameOfMajor(clazz.getMajorId()), nameOfCollege(clazz.getMajorId()),
                    current, incoming, drop, dropPct,
                    significant(drop, dropPct, pct, minDrop)));
        }
        diffs.sort(Comparator.comparingInt(ClassSizeDiff::drop).reversed()
                .thenComparing(ClassSizeDiff::classId));
        return diffs;
    }

    /** 是否存在命中阈值的下调（→ 导入必须显式确认）。 */
    public boolean requiresConfirm(List<ClassSizeDiff> diffs) {
        return diffs.stream().anyMatch(ClassSizeDiff::requiresConfirm);
    }

    /** 当前生效阈值（回显给前端做文案）。 */
    public int confirmPct() {
        return properties.getImportConfig().getClassSizeShrinkConfirmPct();
    }

    public int confirmMinDrop() {
        return properties.getImportConfig().getClassSizeShrinkConfirmMinDrop();
    }

    /**
     * 疑似局部名单的 409 文案：列出下调最狠的班级 + 后果 + 两条可行路径。
     */
    public String confirmMessage(List<ClassSizeDiff> diffs) {
        List<ClassSizeDiff> flagged = diffs.stream().filter(ClassSizeDiff::requiresConfirm).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("本次导入会把 ").append(flagged.size()).append(" 个班级的人数下调超过阈值（下调比例 > ")
                .append(confirmPct()).append("% 且不少于 ").append(confirmMinDrop()).append(" 人）：");
        for (int i = 0; i < Math.min(MAX_LISTED, flagged.size()); i++) {
            sb.append(i == 0 ? "" : "；").append(flagged.get(i).describe());
        }
        if (flagged.size() > MAX_LISTED) {
            sb.append("；等共 ").append(flagged.size()).append(" 个班级");
        }
        sb.append("。班级人数是教师填报数量的上限，下调会立即收紧该班教师可填数量。")
                .append("若这是完整名单，请带 confirmClassSizeShrink=true 重新提交；")
                .append("若只是局部名单，请改用完整名单，或先调用导入预览接口（POST /api/admin/user/import/preview）核对 diff。");
        return sb.toString();
    }

    /**
     * 命中阈值判定：比例超阈值 **且** 下调人数达到下限。
     *
     * <p>只用比例会把小班的正常调整（3 → 2）也拦下来；只用绝对人数会漏掉大班的局部名单
     * （50 → 2 只下调 48，看似不多，实则上限被压到 4%）。两者同时满足才是「疑似局部名单」。</p>
     */
    private boolean significant(int drop, int dropPct, int pct, int minDrop) {
        return drop >= Math.max(1, minDrop) && dropPct > pct;
    }

    private String nameOfMajor(Long majorId) {
        if (majorId == null) {
            return null;
        }
        Major major = majorMapper.selectByIdSoft(majorId);
        return major == null ? null : major.getName();
    }

    private String nameOfCollege(Long majorId) {
        if (majorId == null) {
            return null;
        }
        Major major = majorMapper.selectByIdSoft(majorId);
        if (major == null || major.getCollegeId() == null) {
            return null;
        }
        College college = collegeMapper.selectByIdSoft(major.getCollegeId());
        return college == null ? null : college.getName();
    }
}
