package com.tian.textbook.supplier.mapper;

import com.tian.textbook.supplier.dto.SupplierOrderRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 供货商只读查询 Mapper（物理隔离，SPEC §2 机检红线）。
 *
 * <p>供货商模块独立包 + /api/supplier/** 前缀，禁止 import 学生/教师相关 Mapper；
 * 本 Mapper 自建于 supplier 包内，SQL 直接 join order_form/order_form_item/textbook/
 * sys_user/user_semester_profile/college，resultType 用 supplier 包内 DTO，
 * 不触及学生选购数据（W18：学生汇总仅教材室可见）。</p>
 */
@Mapper
public interface SupplierOrderMapper {

    /** active 学期 reviewed 教师表单明细（按学院分组的数据源） */
    List<SupplierOrderRow> selectReviewedRows(@Param("semesterId") Long semesterId);

    /** 预估行数（导出异步阈值判定，Q16） */
    long countReviewed(@Param("semesterId") Long semesterId);
}
