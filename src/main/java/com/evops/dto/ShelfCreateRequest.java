package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 密集架建档 */
@Data
public class ShelfCreateRequest {

    @NotBlank(message = "密集架编号不能为空")
    private String shelfNo;

    @NotBlank(message = "密集架名称不能为空")
    private String name;

    private String location;
}
