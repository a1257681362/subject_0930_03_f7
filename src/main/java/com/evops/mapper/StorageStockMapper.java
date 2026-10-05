package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StorageStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface StorageStockMapper extends BaseMapper<StorageStock> {

    /**
     * 移动开始原位移出：仅 IN_STOCK 可转 MOVED_OUT 并清空占用键，
     * 返回 0 行即原位库存已被其他移动取走（并发安全）。
     */
    @Update("UPDATE t_storage_stock SET status = 'MOVED_OUT', "
            + "cell_active_key = NULL, box_active_key = NULL, "
            + "out_move_id = #{outMoveId}, out_at = #{outAt}, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'IN_STOCK'")
    int checkoutIfInStock(@Param("id") Long id,
                          @Param("outMoveId") Long outMoveId,
                          @Param("outAt") LocalDateTime outAt);
}
