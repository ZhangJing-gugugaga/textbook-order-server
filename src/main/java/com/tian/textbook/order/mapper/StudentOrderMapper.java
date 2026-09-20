package com.tian.textbook.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.common.annotation.CollegeScope;
import com.tian.textbook.order.entity.StudentOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StudentOrderMapper extends BaseMapper<StudentOrder> {

    /** MP 自动 resultMap（含 submit_snapshot 的 JacksonTypeHandler）：自定义 @Select 需显式引用。 */
    String RESULT_MAP = "com.tian.textbook.order.mapper.StudentOrderMapper.mybatis-plus_StudentOrder";

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM student_order WHERE semester_id = #{semesterId} AND student_id = #{studentId} AND deleted = 0")
    StudentOrder selectBySemesterAndStudent(@Param("semesterId") Long semesterId, @Param("studentId") Long studentId);

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM student_order WHERE id = #{id} AND deleted = 0")
    StudentOrder selectByIdSoft(@Param("id") Long id);

    /** 学生本人历史选购记录（数据隔离：student_id = 本人） */
    @CollegeScope(studentColumn = "student_id")
    @Select("SELECT * FROM student_order WHERE student_id = #{studentId} AND deleted = 0 ORDER BY id DESC")
    List<StudentOrder> selectStudentOrders(@Param("studentId") Long studentId);

    /** 超管全院选购分页（详见 resources/mapper/order/StudentOrderMapper.xml） */
    List<com.tian.textbook.order.dto.StudentOrderListItem> selectAllPage(
            @Param("semesterId") Long semesterId,
            @Param("collegeId") Long collegeId,
            @Param("classId") Long classId,
            @Param("studentName") String studentName,
            @Param("offset") long offset,
            @Param("limit") long limit);

    long countAll(@Param("semesterId") Long semesterId,
                  @Param("collegeId") Long collegeId,
                  @Param("classId") Long classId,
                  @Param("studentName") String studentName);
}
