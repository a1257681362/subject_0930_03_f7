package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 格位预约：搬入前必须预约目标格位。
 * requestId 为幂等锚点：补传/补录/重试同号只形成一条可用结果。
 */
@Data
public class ReservationRequest {

    /** 客户端请求号（幂等锚点）：同一请求号重复提交只回读同一条预约 */
    @NotBlank(message = "请求号不能为空")
    private String requestId;

    @NotNull(message = "目标格位不能为空")
    private Long cellId;

    @NotBlank(message = "箱号不能为空")
    private String boxNo;

    /** 箱重（千克），不得超过格位承重 */
    @NotNull(message = "箱重不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "箱重必须大于 0")
    private BigDecimal boxWeightKg;

    /** 要求温区（可空，提供则格位必须一致） */
    private String requiredZone;

    /** 要求防火等级（可空，提供则格位必须一致） */
    private String requiredFireRating;

    /** 预约超时时长（分钟），缺省取 museum.storage.reserve-timeout-min */
    private Integer ttlMinutes;

    /** 操作人员（预约人） */
    @NotBlank(message = "操作人员不能为空")
    private String operator;

    private String note;
}
