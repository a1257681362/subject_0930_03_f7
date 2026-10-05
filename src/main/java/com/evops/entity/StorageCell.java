package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 库位（格位）：按温区、防火等级、承重维护；可挂在密集架下。
 * zone: NORMAL 常温 / CONSTANT 恒温 / LOW_TEMP 低温；
 * fireRating: F1 / F2 / F3；sensitive=1 为敏感位置（按馆员权限脱敏/隐藏）。
 * status: ACTIVE 可用 / DISABLED 停用（停用不可预约，历史保留）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_cell")
public class StorageCell extends BaseEntity {

    private String cellNo;

    /** 所属密集架（可空：普通库位不挂密集架） */
    private Long shelfId;

    /** NORMAL / CONSTANT / LOW_TEMP */
    private String zone;

    /** F1 / F2 / F3 */
    private String fireRating;

    /** 承重上限（千克） */
    private BigDecimal loadCapacityKg;

    /** 0 普通位置 / 1 敏感位置 */
    private Integer sensitive;

    private String location;

    /** ACTIVE / DISABLED */
    private String status;
}
