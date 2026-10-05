package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StorageMove;
import org.apache.ibatis.annotations.Select;

public interface StorageMoveMapper extends BaseMapper<StorageMove> {

    /** 移动单号取号（H2 序列），移动号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_storage_move_no")
    Long nextMoveNo();
}
