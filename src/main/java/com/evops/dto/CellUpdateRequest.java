package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * 库位维护：温区、防火等级、承重、敏感标记与停用均可维护；
 * 字段缺省表示不修改，维护动作写库房台账留痕。
 */
@Data
public class CellUpdateRequest {

    /** 温区：NORMAL 常温 / CONSTANT 恒温 / LOW_TEMP 低温 */
    private String zone;

    /** 防火等级：F1 / F2 / F3 */
    private String fireRating;

    /** 承重上限（千克） */
    @DecimalMin(value = "0", inclusive = false, message = "承重必须大于 0")
    private BigDecimal loadCapacityKg;

    /** 0 普通位置 / 1 敏感位置 */
    private Integer sensitive;

    private String location;

    /** ACTIVE 可用 / DISABLED 停用 */
    private String status;

    /** 操作人员（维护人，留痕用） */
    private String operator;
}
