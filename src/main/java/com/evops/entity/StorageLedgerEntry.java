package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库房台账：只追加、不修改、不删除。
 * 库位建档/维护、密集架建档、预约、超时释放、移动开始/完成全部留痕并记录操作人员。
 */
@Data
@TableName("t_storage_ledger")
public class StorageLedgerEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long cellId;

    private Long reservationId;

    private Long moveId;

    private String boxNo;

    /** SHELF_CREATE / CELL_CREATE / CELL_UPDATE / RESERVE / RESERVE_RELEASE / MOVE_START / MOVE_COMPLETE */
    private String action;

    private String bizNo;

    private String summary;

    /** 操作人员 */
    private String operator;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
