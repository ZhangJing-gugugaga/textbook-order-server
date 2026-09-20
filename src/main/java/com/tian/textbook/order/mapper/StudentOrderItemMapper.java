package com.tian.textbook.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.order.entity.StudentOrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StudentOrderItemMapper extends BaseMapper<StudentOrderItem> {

    @Select("SELECT * FROM student_order_item WHERE order_id = #{orderId} AND deleted = 0")
    List<StudentOrderItem> selectByOrderId(@Param("orderId") Long orderId);

    @Select("<script>SELECT * FROM student_order_item WHERE order_id IN "
            + "<foreach item='o' collection='orderIds' open='(' separator=',' close=')'>#{o}</foreach>"
            + " AND deleted = 0</script>")
    List<StudentOrderItem> selectByOrderIds(@Param("orderIds") List<Long> orderIds);

    /** 重提 = 整单覆盖：先逻辑删旧明细 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE student_order_item SET deleted = #{now} WHERE order_id = #{orderId} AND deleted = 0")
    int softDeleteByOrder(@Param("orderId") Long orderId, @Param("now") Long now);

    /**
     * 学生选购汇总原始行（参考用量，W18）：仅统计已提交（submitted）选购单；
     * 按学院/班级/教材的分组聚合在 Service 层完成（submit_snapshot 为提交时归属快照，
     * 异动不影响历史归属，W15）。
     */
    List<com.tian.textbook.order.dto.StudentOrderSummaryRow> selectSummaryRows(@Param("semesterId") Long semesterId);
}
