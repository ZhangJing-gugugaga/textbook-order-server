package com.tian.textbook.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.order.entity.OrderFormItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderFormItemMapper extends BaseMapper<OrderFormItem> {

    @Select("SELECT * FROM order_form_item WHERE form_id = #{formId} AND deleted = 0")
    List<OrderFormItem> selectByFormId(@Param("formId") Long formId);

    @Select("<script>SELECT * FROM order_form_item WHERE form_id IN "
            + "<foreach item='f' collection='formIds' open='(' separator=',' close=')'>#{f}</foreach>"
            + " AND deleted = 0</script>")
    List<OrderFormItem> selectByFormIds(@Param("formIds") List<Long> formIds);

    /** 重提 = 整单覆盖：先逻辑删旧明细（deleted 写时间戳，W9） */
    @org.apache.ibatis.annotations.Update(
            "UPDATE order_form_item SET deleted = #{now} WHERE form_id = #{formId} AND deleted = 0")
    int softDeleteByForm(@Param("formId") Long formId, @Param("now") Long now);

    /** 汇总/导出：已审核通过表单的明细（课程×班级×教材×数量） */
    List<java.util.Map<String, Object>> selectReviewedItems(@Param("semesterId") Long semesterId,
                                                            @Param("collegeId") Long collegeId);

    /**
     * 汇总/导出的行数（阈值判定用）：与 {@link #selectReviewedItems} 同口径，只取 COUNT。
     * 导出前的预估不应把整表结果物化进内存，仅为与阈值比较一次。
     */
    long countReviewedItems(@Param("semesterId") Long semesterId, @Param("collegeId") Long collegeId);
}
