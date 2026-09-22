package com.tian.textbook.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.common.annotation.CollegeScope;
import com.tian.textbook.order.entity.OrderForm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderFormMapper extends BaseMapper<OrderForm> {

    /**
     * MP 自动 resultMap（@TableName(autoResultMap=true) 生成，含 field_check_result 的
     * JacksonTypeHandler）：自定义 @Select 不会自动套用，需显式引用，否则 JSON 列被
     * 自动映射静默丢弃（field_check_result 读回为 null，违背 SPEC §11.4「详情含 field_check_result」）。
     */
    String RESULT_MAP = "com.tian.textbook.order.mapper.OrderFormMapper.mybatis-plus_OrderForm";

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM order_form WHERE semester_id = #{semesterId} AND teacher_id = #{teacherId} AND deleted = 0")
    OrderForm selectBySemesterAndTeacher(@Param("semesterId") Long semesterId, @Param("teacherId") Long teacherId);

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM order_form WHERE id = #{id} AND deleted = 0")
    OrderForm selectByIdSoft(@Param("id") Long id);

    /**
     * 加行锁读取（提交路径专用）：把「读状态 → 整单覆盖写」与并发审核串行化。
     *
     * <p>提交侧的终态校验是「读后判断」，与管理员审核并发时拦不住：T1 读到 pending_review
     * → T2 审核 pass 提交（reviewed）→ T1 的 {@code updateById}（按主键无条件）写回
     * pending_review 并清空审核字段，审批结论被静默撤销，而 audit_log 里仍留着「已审核通过」。
     * 提交事务内先对该行加 X 锁并重读状态，两种交织都收敛：审核先提交 → 这里读到 reviewed
     * 直接按终态拒绝；提交先持锁 → 审核的 CAS（status + content_version 双谓词）在提交后
     * 因版本已 +1 而命中 0 行 → 409。锁粒度是单个表单行（一教师一学期一单），冲突面仅
     * 「同一表单的提交与审核」，不波及其它教师。</p>
     */
    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM order_form WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    OrderForm selectByIdForUpdate(@Param("id") Long id);

    /** 教师本人历史提交记录（数据隔离：teacher_id = 本人） */
    @ResultMap(RESULT_MAP)
    @CollegeScope(teacherColumn = "teacher_id")
    @Select("SELECT * FROM order_form WHERE teacher_id = #{teacherId} AND deleted = 0 ORDER BY id DESC")
    List<OrderForm> selectTeacherForms(@Param("teacherId") Long teacherId);

    /**
     * 秘书本院表单分页（跨表学院范围：经 user_semester_profile 归属过滤，collegeId 由
     * Service 从当前用户上下文显式传入，隔离效果等同 @CollegeScope）。
     * 详见 resources/mapper/order/OrderFormMapper.xml。
     */
    List<com.tian.textbook.order.dto.OrderFormListItem> selectCollegeFormsPage(
            @Param("collegeId") Long collegeId,
            @Param("semesterId") Long semesterId,
            @Param("status") String status,
            @Param("teacherName") String teacherName,
            @Param("offset") long offset,
            @Param("limit") long limit);

    long countCollegeForms(@Param("collegeId") Long collegeId,
                           @Param("semesterId") Long semesterId,
                           @Param("status") String status,
                           @Param("teacherName") String teacherName);

    /**
     * 超管全院表单分页（复核工作台）。详见 resources/mapper/order/OrderFormMapper.xml。
     */
    List<com.tian.textbook.order.dto.OrderFormListItem> selectAllFormsPage(
            @Param("semesterId") Long semesterId,
            @Param("collegeId") Long collegeId,
            @Param("status") String status,
            @Param("teacherName") String teacherName,
            @Param("offset") long offset,
            @Param("limit") long limit);

    long countAllForms(@Param("semesterId") Long semesterId,
                       @Param("collegeId") Long collegeId,
                       @Param("status") String status,
                       @Param("teacherName") String teacherName);

    /** 看板/导出用：按学院聚合教师提交进度。 */
    List<java.util.Map<String, Object>> countGroupByCollegeAndStatus(@Param("semesterId") Long semesterId);

    /**
     * 内容版本 +1（教师每次提交/补正整单覆盖后调用）。
     *
     * <p>审核端以 {@code content_version} 作为 CAS 谓词的一部分，识别「管理员打开详情后
     * 教师又重提过」——此时 status 仍是 pending_review，但明细已被整单覆盖。</p>
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE order_form SET content_version = COALESCE(content_version, 0) + 1 WHERE id = #{id} AND deleted = 0")
    int bumpContentVersion(@Param("id") Long id);
}
