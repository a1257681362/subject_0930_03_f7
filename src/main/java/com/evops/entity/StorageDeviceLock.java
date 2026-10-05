package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 互斥设备时段锁：移动开始即对源/目的密集架各占一行。
 *
 * <p>activeKey = 密集架 id（数据库唯一）——同一密集架同一时段只允许一个操作；
 * 移动完成后置 RELEASED 并清键，历史保留。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_device_lock")
public class StorageDeviceLock extends BaseEntity {

    private Long shelfId;

    private Long moveId;

    /** ACTIVE 占用中 / RELEASED 已释放 */
    private String status;

    /** 占用中 = 密集架 id，释放后置 NULL */
    private Long activeKey;

    private LocalDateTime lockedAt;

    private LocalDateTime releasedAt;
}
