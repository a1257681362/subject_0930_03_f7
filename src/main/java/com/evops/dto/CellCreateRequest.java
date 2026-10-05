package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 库位建档：按温区、防火等级、承重维护 */
@Data
public class CellCreateRequest {

    @NotBlank(message = "库位编号不能为空")
    private String cellNo;

    /** 所属密集架（可空：普通库位不挂密集架） */
    private Long shelfId;

    /** 温区：NORMAL 常温 / CONSTANT 恒温 / LOW_TEMP 低温 */
    @NotBlank(message = "温区不能为空")
    private String zone;

    /** 防火等级：F1 / F2 / F3 */
    @NotBlank(message = "防火等级不能为空")
    private String fireRating;

    /** 承重上限（千克） */
    @NotNull(message = "承重不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "承重必须大于 0")
    private BigDecimal loadCapacityKg;

    /** 0 普通位置 / 1 敏感位置（缺省普通） */
    private Integer sensitive;

    private String location;
}
