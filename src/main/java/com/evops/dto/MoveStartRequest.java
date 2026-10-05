package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 移动单登记：记录原位、目的位（取预约格位）、箱号（取预约箱号）与操作人员。
 * requestId 为幂等锚点：重试只形成一条移动单。
 */
@Data
public class MoveStartRequest {

    /** 客户端请求号（幂等锚点） */
    @NotBlank(message = "请求号不能为空")
    private String requestId;

    /** 关联预约（搬入前必须预约目标格位） */
    @NotNull(message = "预约不能为空")
    private Long reservationId;

    /** 原位（可空：外部搬入无原位；提供则该格位必须有此箱在库） */
    private Long fromCellId;

    /** 操作人员（移动执行人） */
    @NotBlank(message = "操作人员不能为空")
    private String operator;
}
