package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.StorageLedgerEntry;
import com.evops.mapper.StorageLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库房台账：只追加、不修改、不删除。
 * 库位建档/维护、密集架建档、预约、超时释放、移动开始/完成全部在此留痕，
 * 每条记录操作人员——库房动作全程可追溯。
 */
@Service
public class StorageLedgerService {

    private final StorageLedgerMapper ledgerMapper;

    public StorageLedgerService(StorageLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long cellId, Long reservationId, Long moveId, String boxNo,
                       String action, String bizNo, String summary,
                       String operator, LocalDateTime occurredAt) {
        StorageLedgerEntry entry = new StorageLedgerEntry();
        entry.setCellId(cellId);
        entry.setReservationId(reservationId);
        entry.setMoveId(moveId);
        entry.setBoxNo(boxNo);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOperator(operator);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    /** 库房台账查询：可按格位 / 箱号过滤，按时间序列返回 */
    public List<StorageLedgerEntry> list(Long cellId, String boxNo) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<StorageLedgerEntry>()
                .eq(cellId != null, StorageLedgerEntry::getCellId, cellId)
                .eq(boxNo != null && !boxNo.isEmpty(), StorageLedgerEntry::getBoxNo, boxNo)
                .orderByAsc(StorageLedgerEntry::getOccurredAt)
                .orderByAsc(StorageLedgerEntry::getId));
    }
}
