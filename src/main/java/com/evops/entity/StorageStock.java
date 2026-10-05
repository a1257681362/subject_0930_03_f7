package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 库存：箱号当前所在格位。
 *
 * <p>IN_STOCK 行持 cellActiveKey / boxActiveKey 唯一键：一格一箱、一箱一位；
 * 移出置 MOVED_OUT 并清键，历史保留。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_stock")
public class StorageStock extends BaseEntity {

    private Long cellId;

    private String boxNo;

    /** 搬入移动单 */
    private Long moveId;

    /** 操作人员（搬入执行人） */
    private String operator;

    /** IN_STOCK / MOVED_OUT */
    private String status;

    /** 在库持有的格位占用键（= cellId），移出置 NULL */
    private Long cellActiveKey;

    /** 在库持有的箱号定位键（= boxNo），移出置 NULL */
    private String boxActiveKey;

    private LocalDateTime inAt;

    private LocalDateTime outAt;

    /** 移出移动单 */
    private Long outMoveId;
}
