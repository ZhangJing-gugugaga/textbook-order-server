package com.tian.textbook.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.common.annotation.CollegeScope;
import com.tian.textbook.order.entity.OrderForm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderFormMapper extends BaseMapper<OrderForm> {

    @Select("SELECT * FROM order_form WHERE semester_id = #{semesterId} AND teacher_id = #{teacherId} AND deleted = 0")
    OrderForm selectBySemesterAndTeacher(@Param("semesterId") Long semesterId, @Param("teacherId") Long teacherId);

    @Select("SELECT * FROM order_form WHERE id = #{id} AND deleted = 0")
    OrderForm selectByIdSoft(@Param("id") Long id);

    /** 教师本人历史提交记录（数据隔离：teacher_id = 本人） */
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
}
