package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 格位预约：搬入前必须预约目标格位。
 *
 * <p>requestId 为幂等锚点：补传/补录/重试凭同一 requestId 只回读同一条，
 * 全库只形成一条可用结果。</p>
 *
 * <p>status: RESERVED 有效 / CONSUMED 已随移动单开始 / RELEASED 已释放。
 * cellActiveKey / boxActiveKey 仅 RESERVED/CONSUMED 持有（数据库唯一），
 * 移动完成或释放后清空——格位占用从预约到库存全程无空窗。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_reservation")
public class StorageReservation extends BaseEntity {

    private String reserveNo;

    /** 幂等锚点：客户端请求号，补传/补录/重试同号只形成一条结果 */
    private String requestId;

    private Long cellId;

    private String boxNo;

    /** 箱重（千克），预约时校验不超过格位承重 */
    private BigDecimal boxWeightKg;

    /** 要求的温区（可空，提供则必须与格位一致） */
    private String requiredZone;

    /** 要求的防火等级（可空，提供则必须与格位一致） */
    private String requiredFireRating;

    /** 操作人员（预约人） */
    private String operator;

    private String note;

    /** RESERVED / CONSUMED / RELEASED */
    private String status;

    /** 预约超时时间，超时未开始移动由定时扫描自动释放 */
    private LocalDateTime expireAt;

    /** 释放原因：TIMEOUT 超时自动释放 */
    private String releasedReason;

    private LocalDateTime releasedAt;

    /** 有效预约持有的格位占用键（= cellId），释放/移动完成后置 NULL */
    private Long cellActiveKey;

    /** 有效预约持有的箱号在途键（= boxNo），释放/移动完成后置 NULL */
    private String boxActiveKey;
}
