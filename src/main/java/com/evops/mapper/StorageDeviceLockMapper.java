package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StorageDeviceLock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface StorageDeviceLockMapper extends BaseMapper<StorageDeviceLock> {

    /** 移动完成释放该单占用的全部互斥设备时段锁（清空占用键，历史保留） */
    @Update("UPDATE t_storage_device_lock SET status = 'RELEASED', active_key = NULL, "
            + "released_at = #{at}, update_time = CURRENT_TIMESTAMP "
            + "WHERE move_id = #{moveId} AND status = 'ACTIVE'")
    int releaseByMove(@Param("moveId") Long moveId, @Param("at") LocalDateTime at);
}
