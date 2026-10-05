package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 移动完成登记：库存落目的位、释放互斥设备时段锁 */
@Data
public class MoveCompleteRequest {

    /** 完成登记人（不覆盖移动执行人） */
    @NotBlank(message = "完成登记人不能为空")
    private String operator;
}
