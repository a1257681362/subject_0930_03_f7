package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 移动单：记录原位、目的位、箱号与操作人员。
 *
 * <p>requestId 为幂等锚点（重试只形成一条移动单）。
 * status: MOVING 移动中（已占用源/目的密集架时段锁）/ DONE 已完成。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_move")
public class StorageMove extends BaseEntity {

    private String moveNo;

    /** 幂等锚点：客户端请求号 */
    private String requestId;

    /** 关联的格位预约（搬入前必须预约目标格位） */
    private Long reservationId;

    private String boxNo;

    /** 原位（可空：外部搬入无原位） */
    private Long fromCellId;

    /** 目的位（= 预约格位） */
    private Long toCellId;

    /** 操作人员（移动执行人） */
    private String operator;

    /** MOVING / DONE */
    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 完成登记人（不覆盖移动执行人） */
    private String finishedBy;
}
