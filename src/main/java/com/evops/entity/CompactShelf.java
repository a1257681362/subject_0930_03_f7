package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 密集架：互斥设备，同一时段只允许一个操作。
 * status: ACTIVE 在用 / DISABLED 停用（停用不接受新格位，历史保留）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compact_shelf")
public class CompactShelf extends BaseEntity {

    private String shelfNo;

    private String name;

    private String location;

    /** ACTIVE / DISABLED */
    private String status;
}
