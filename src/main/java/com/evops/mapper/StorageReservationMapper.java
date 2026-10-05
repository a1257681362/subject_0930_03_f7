package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StorageReservation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface StorageReservationMapper extends BaseMapper<StorageReservation> {

    /** 预约单号取号（H2 序列），预约号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_storage_reserve_no")
    Long nextReserveNo();

    /**
     * 移动开始消费预约：仅 RESERVED 可转 CONSUMED，返回 0 行即已被释放/消费。
     * 占用键不清空，继续持键到移动完成交棒给库存行（格位占用无空窗）。
     */
    @Update("UPDATE t_storage_reservation SET status = 'CONSUMED', "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'RESERVED'")
    int consumeIfReserved(@Param("id") Long id);

    /**
     * 超时自动释放：仅 RESERVED 可转 RELEASED 并清空占用键，返回 0 行即已被消费
     * （已开始移动的格位绝不释放）；命中才允许补写台账，重启重扫幂等。
     */
    @Update("UPDATE t_storage_reservation SET status = 'RELEASED', "
            + "released_reason = 'TIMEOUT', released_at = #{at}, "
            + "cell_active_key = NULL, box_active_key = NULL, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'RESERVED'")
    int releaseIfReserved(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** 移动完成交棒：清空预约持有的占用键（库存行同事务接管格位与箱号） */
    @Update("UPDATE t_storage_reservation SET cell_active_key = NULL, box_active_key = NULL, "
            + "update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'CONSUMED'")
    int clearActiveKeys(@Param("id") Long id);
}
