package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumStorageProperties;
import com.evops.dto.CellCreateRequest;
import com.evops.dto.CellUpdateRequest;
import com.evops.dto.MoveCompleteRequest;
import com.evops.dto.MoveStartRequest;
import com.evops.dto.ReservationRequest;
import com.evops.dto.ShelfCreateRequest;
import com.evops.entity.CompactShelf;
import com.evops.entity.StorageCell;
import com.evops.entity.StorageDeviceLock;
import com.evops.entity.StorageMove;
import com.evops.entity.StorageReservation;
import com.evops.entity.StorageStock;
import com.evops.mapper.CompactShelfMapper;
import com.evops.mapper.StorageCellMapper;
import com.evops.mapper.StorageDeviceLockMapper;
import com.evops.mapper.StorageMoveMapper;
import com.evops.mapper.StorageReservationMapper;
import com.evops.mapper.StorageStockMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物库房库位与密集架预约核心服务：
 *
 * <ol>
 *   <li><b>库位维护</b>：库位按温区、防火等级、承重登记与维护，可挂密集架；
 *       密集架是互斥设备。</li>
 *   <li><b>预约</b>：搬入前必须预约目标格位；requestId 幂等锚点保证补传/补录/
 *       重试只形成一条可用结果；格位与箱号占用键唯一约束兜底并发。</li>
 *   <li><b>移动单</b>：记录原位、目的位、箱号与操作人员；开始即占用源/目的
 *       密集架时段锁——互斥设备同一时段只允许一个操作。</li>
 *   <li><b>超时释放</b>：预约超时由扫描器条件更新释放，已开始移动的格位
 *       （预约已转 CONSUMED）绝不释放。</li>
 *   <li><b>库存查询</b>：按馆员权限对敏感位置脱敏（RESTORER）或隐藏（VIEWER）。</li>
 * </ol>
 */
@Service
public class StorageService {

    /** 敏感位置掩码 */
    static final String MASK = "***";

    private final CompactShelfMapper shelfMapper;
    private final StorageCellMapper cellMapper;
    private final StorageReservationMapper reservationMapper;
    private final StorageMoveMapper moveMapper;
    private final StorageStockMapper stockMapper;
    private final StorageDeviceLockMapper lockMapper;
    private final StorageLedgerService ledgerService;
    private final MuseumStorageProperties properties;
    private final TransactionTemplate transactionTemplate;

    public StorageService(CompactShelfMapper shelfMapper,
                          StorageCellMapper cellMapper,
                          StorageReservationMapper reservationMapper,
                          StorageMoveMapper moveMapper,
                          StorageStockMapper stockMapper,
                          StorageDeviceLockMapper lockMapper,
                          StorageLedgerService ledgerService,
                          MuseumStorageProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.shelfMapper = shelfMapper;
        this.cellMapper = cellMapper;
        this.reservationMapper = reservationMapper;
        this.moveMapper = moveMapper;
        this.stockMapper = stockMapper;
        this.lockMapper = lockMapper;
        this.ledgerService = ledgerService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================================================================
    // 密集架
    // ================================================================

    @Transactional
    public CompactShelf createShelf(ShelfCreateRequest req) {
        if (shelfMapper.selectCount(new LambdaQueryWrapper<CompactShelf>()
                .eq(CompactShelf::getShelfNo, req.getShelfNo())) > 0) {
            throw new IllegalArgumentException("密集架编号已存在: " + req.getShelfNo());
        }
        CompactShelf shelf = new CompactShelf();
        shelf.setShelfNo(req.getShelfNo());
        shelf.setName(req.getName());
        shelf.setLocation(req.getLocation());
        shelf.setStatus(STORAGE_ACTIVE);
        shelfMapper.insert(shelf);
        ledgerService.append(null, null, null, null, ACTION_SHELF_CREATE, shelf.getShelfNo(),
                "密集架建档：" + shelf.getShelfNo() + "（" + shelf.getName() + "）", null, null);
        return shelf;
    }

    public List<CompactShelf> listShelves() {
        return shelfMapper.selectList(new LambdaQueryWrapper<CompactShelf>()
                .orderByAsc(CompactShelf::getId));
    }

    public CompactShelf requireShelf(Long shelfId) {
        CompactShelf shelf = shelfMapper.selectById(shelfId);
        if (shelf == null) {
            throw new IllegalArgumentException("密集架不存在: id=" + shelfId);
        }
        return shelf;
    }

    // ================================================================
    // 库位：按温区、防火等级、承重维护
    // ================================================================

    @Transactional
    public StorageCell createCell(CellCreateRequest req) {
        if (cellMapper.selectCount(new LambdaQueryWrapper<StorageCell>()
                .eq(StorageCell::getCellNo, req.getCellNo())) > 0) {
            throw new IllegalArgumentException("库位编号已存在: " + req.getCellNo());
        }
        validateZone(req.getZone());
        validateFireRating(req.getFireRating());
        if (req.getShelfId() != null) {
            CompactShelf shelf = requireShelf(req.getShelfId());
            if (!STORAGE_ACTIVE.equals(shelf.getStatus())) {
                throw new IllegalArgumentException("密集架已停用，不能新增格位: " + shelf.getShelfNo());
            }
        }
        StorageCell cell = new StorageCell();
        cell.setCellNo(req.getCellNo());
        cell.setShelfId(req.getShelfId());
        cell.setZone(req.getZone());
        cell.setFireRating(req.getFireRating());
        cell.setLoadCapacityKg(req.getLoadCapacityKg());
        cell.setSensitive(req.getSensitive() != null && req.getSensitive() == 1 ? 1 : 0);
        cell.setLocation(req.getLocation());
        cell.setStatus(STORAGE_ACTIVE);
        cellMapper.insert(cell);
        ledgerService.append(cell.getId(), null, null, null, ACTION_CELL_CREATE, cell.getCellNo(),
                "库位建档：" + cell.getCellNo() + "，温区 " + cell.getZone()
                        + "，防火等级 " + cell.getFireRating()
                        + "，承重 " + cell.getLoadCapacityKg() + "kg"
                        + (cell.getSensitive() == 1 ? "，敏感位置" : ""), null, null);
        return cell;
    }

    /** 库位维护：温区/防火等级/承重/敏感标记/停用，维护动作写台账留痕 */
    @Transactional
    public StorageCell updateCell(Long cellId, CellUpdateRequest req) {
        StorageCell cell = requireCell(cellId);
        List<String> changes = new ArrayList<>();
        if (req.getZone() != null) {
            validateZone(req.getZone());
            changes.add("温区 " + cell.getZone() + "→" + req.getZone());
            cell.setZone(req.getZone());
        }
        if (req.getFireRating() != null) {
            validateFireRating(req.getFireRating());
            changes.add("防火等级 " + cell.getFireRating() + "→" + req.getFireRating());
            cell.setFireRating(req.getFireRating());
        }
        if (req.getLoadCapacityKg() != null) {
            changes.add("承重 " + cell.getLoadCapacityKg() + "→" + req.getLoadCapacityKg() + "kg");
            cell.setLoadCapacityKg(req.getLoadCapacityKg());
        }
        if (req.getSensitive() != null) {
            if (req.getSensitive() != 0 && req.getSensitive() != 1) {
                throw new IllegalArgumentException("敏感标记只能为 0 或 1");
            }
            changes.add(req.getSensitive() == 1 ? "标记为敏感位置" : "取消敏感位置标记");
            cell.setSensitive(req.getSensitive());
        }
        if (req.getLocation() != null) {
            cell.setLocation(req.getLocation());
            changes.add("位置说明更新");
        }
        if (req.getStatus() != null) {
            if (!STORAGE_ACTIVE.equals(req.getStatus()) && !STORAGE_DISABLED.equals(req.getStatus())) {
                throw new IllegalArgumentException("库位状态非法: " + req.getStatus());
            }
            changes.add(STORAGE_DISABLED.equals(req.getStatus()) ? "停用" : "启用");
            cell.setStatus(req.getStatus());
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("没有需要维护的字段");
        }
        cellMapper.updateById(cell);
        ledgerService.append(cell.getId(), null, null, null, ACTION_CELL_UPDATE, cell.getCellNo(),
                "库位维护：" + cell.getCellNo() + "，" + String.join("；", changes),
                req.getOperator(), null);
        return cell;
    }

    public StorageCell requireCell(Long cellId) {
        StorageCell cell = cellMapper.selectById(cellId);
        if (cell == null) {
            throw new IllegalArgumentException("库位不存在: id=" + cellId);
        }
        return cell;
    }

    /** 库位列表：按馆员权限对敏感位置脱敏（RESTORER）或隐藏（VIEWER） */
    public List<Map<String, Object>> listCells(String role, String zone, String fireRating, Long shelfId) {
        List<StorageCell> cells = cellMapper.selectList(new LambdaQueryWrapper<StorageCell>()
                .eq(zone != null && !zone.isEmpty(), StorageCell::getZone, zone)
                .eq(fireRating != null && !fireRating.isEmpty(), StorageCell::getFireRating, fireRating)
                .eq(shelfId != null, StorageCell::getShelfId, shelfId)
                .orderByAsc(StorageCell::getId));
        Map<Long, String> shelfNos = shelfNos();
        List<Map<String, Object>> result = new ArrayList<>();
        for (StorageCell cell : cells) {
            Map<String, Object> view = cellView(cell, shelfNos.get(cell.getShelfId()), role);
            if (view != null) {
                result.add(view);
            }
        }
        return result;
    }

    // ================================================================
    // 格位预约：搬入前必须预约；requestId 幂等锚点
    // ================================================================

    /**
     * 预约目标格位。补传/补录/重试凭同一 requestId 只回读同一条预约，
     * 全库只形成一条可用结果；并发抢注靠唯一约束整体回滚后回读。
     */
    public ReserveOutcome reserve(ReservationRequest req) {
        StorageReservation existing = findReservationByRequestId(req.getRequestId());
        if (existing != null) {
            return new ReserveOutcome(existing, false);
        }
        try {
            return transactionTemplate.execute(status -> doReserve(req));
        } catch (DuplicateKeyException e) {
            // 并发下同号请求已由另一事务播种：回读同一条，不产生第二条结果
            StorageReservation again = findReservationByRequestId(req.getRequestId());
            if (again != null) {
                return new ReserveOutcome(again, false);
            }
            throw new IllegalArgumentException("格位已有有效预约或箱号已有在途预约，预约冲突");
        }
    }

    private ReserveOutcome doReserve(ReservationRequest req) {
        // 事务内二次检查，挡住快速路径后的并发重试
        StorageReservation existing = findReservationByRequestId(req.getRequestId());
        if (existing != null) {
            return new ReserveOutcome(existing, false);
        }
        StorageCell cell = requireCell(req.getCellId());
        if (!STORAGE_ACTIVE.equals(cell.getStatus())) {
            throw new IllegalArgumentException("库位已停用，不能预约: " + cell.getCellNo());
        }
        if (req.getRequiredZone() != null && !req.getRequiredZone().isEmpty()) {
            validateZone(req.getRequiredZone());
            if (!req.getRequiredZone().equals(cell.getZone())) {
                throw new IllegalArgumentException("温区不匹配：要求 " + req.getRequiredZone()
                        + "，库位 " + cell.getCellNo() + " 为 " + cell.getZone());
            }
        }
        if (req.getRequiredFireRating() != null && !req.getRequiredFireRating().isEmpty()) {
            validateFireRating(req.getRequiredFireRating());
            if (!req.getRequiredFireRating().equals(cell.getFireRating())) {
                throw new IllegalArgumentException("防火等级不匹配：要求 " + req.getRequiredFireRating()
                        + "，库位 " + cell.getCellNo() + " 为 " + cell.getFireRating());
            }
        }
        if (req.getBoxWeightKg().compareTo(cell.getLoadCapacityKg()) > 0) {
            throw new IllegalArgumentException("超过库位承重：箱重 " + req.getBoxWeightKg()
                    + "kg，库位 " + cell.getCellNo() + " 承重 " + cell.getLoadCapacityKg() + "kg");
        }
        int ttl = req.getTtlMinutes() != null ? req.getTtlMinutes() : properties.getReserveTimeoutMin();
        if (ttl < 0) {
            throw new IllegalArgumentException("预约超时时长不能为负");
        }
        // 占用预检（友好报错），唯一约束兜底并发
        if (stockMapper.selectCount(new LambdaQueryWrapper<StorageStock>()
                .eq(StorageStock::getCellActiveKey, cell.getId())) > 0) {
            throw new IllegalArgumentException("格位已有在库箱，不能预约: " + cell.getCellNo());
        }
        if (reservationMapper.selectCount(new LambdaQueryWrapper<StorageReservation>()
                .eq(StorageReservation::getCellActiveKey, cell.getId())) > 0) {
            throw new IllegalArgumentException("格位已有有效预约: " + cell.getCellNo());
        }
        if (reservationMapper.selectCount(new LambdaQueryWrapper<StorageReservation>()
                .eq(StorageReservation::getBoxActiveKey, req.getBoxNo())) > 0) {
            throw new IllegalArgumentException("箱号已有在途预约: " + req.getBoxNo());
        }

        LocalDateTime now = LocalDateTime.now();
        StorageReservation reservation = new StorageReservation();
        reservation.setReserveNo("RS-" + String.format("%06d", reservationMapper.nextReserveNo()));
        reservation.setRequestId(req.getRequestId());
        reservation.setCellId(cell.getId());
        reservation.setBoxNo(req.getBoxNo());
        reservation.setBoxWeightKg(req.getBoxWeightKg());
        reservation.setRequiredZone(req.getRequiredZone());
        reservation.setRequiredFireRating(req.getRequiredFireRating());
        reservation.setOperator(req.getOperator());
        reservation.setNote(req.getNote());
        reservation.setStatus(RESV_RESERVED);
        reservation.setExpireAt(now.plusMinutes(ttl));
        reservation.setCellActiveKey(cell.getId());
        reservation.setBoxActiveKey(req.getBoxNo());
        reservationMapper.insert(reservation);
        ledgerService.append(cell.getId(), reservation.getId(), null, reservation.getBoxNo(),
                ACTION_RESERVE, reservation.getReserveNo(),
                "预约格位 " + cell.getCellNo() + "，箱号 " + reservation.getBoxNo()
                        + "，箱重 " + reservation.getBoxWeightKg() + "kg，"
                        + reservation.getExpireAt() + " 超时自动释放",
                req.getOperator(), now);
        return new ReserveOutcome(reservation, true);
    }

    public StorageReservation requireReservation(Long reservationId) {
        StorageReservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            throw new IllegalArgumentException("预约不存在: id=" + reservationId);
        }
        return reservation;
    }

    public List<StorageReservation> listReservations(String status) {
        return reservationMapper.selectList(new LambdaQueryWrapper<StorageReservation>()
                .eq(status != null && !status.isEmpty(), StorageReservation::getStatus, status)
                .orderByAsc(StorageReservation::getId));
    }

    private StorageReservation findReservationByRequestId(String requestId) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<StorageReservation>()
                .eq(StorageReservation::getRequestId, requestId));
    }

    // ================================================================
    // 移动单：记录原位、目的位、箱号与操作人员；互斥设备同一时段只允许一个操作
    // ================================================================

    /**
     * 登记移动单（开始移动）。requestId 幂等锚点：重试只形成一条移动单；
     * 开始即占用源/目的密集架时段锁，同一密集架同时段第二个操作被拦截。
     */
    public MoveOutcome startMove(MoveStartRequest req) {
        StorageMove existing = findMoveByRequestId(req.getRequestId());
        if (existing != null) {
            return new MoveOutcome(existing, false);
        }
        try {
            return transactionTemplate.execute(status -> doStartMove(req));
        } catch (DuplicateKeyException e) {
            // 同号重试：回读同一条；否则为互斥设备时段锁冲突
            StorageMove again = findMoveByRequestId(req.getRequestId());
            if (again != null) {
                return new MoveOutcome(again, false);
            }
            throw new IllegalArgumentException("互斥设备同一时段只允许一个操作：密集架正被其他移动占用");
        }
    }

    private MoveOutcome doStartMove(MoveStartRequest req) {
        // 事务内二次检查，挡住快速路径后的并发重试
        StorageMove existing = findMoveByRequestId(req.getRequestId());
        if (existing != null) {
            return new MoveOutcome(existing, false);
        }
        StorageReservation reservation = requireReservation(req.getReservationId());
        if (RESV_RELEASED.equals(reservation.getStatus())) {
            throw new IllegalArgumentException("预约已释放，不能开始移动: " + reservation.getReserveNo());
        }
        if (RESV_CONSUMED.equals(reservation.getStatus())) {
            throw new IllegalArgumentException("预约已随其他移动单开始，不能重复使用: "
                    + reservation.getReserveNo());
        }
        StorageCell toCell = requireCell(reservation.getCellId());
        String boxNo = reservation.getBoxNo();

        // 原位：提供则该格位必须有此箱在库；箱在库于其他格位时原位必填且必须一致
        StorageCell fromCell = null;
        StorageStock sourceStock = stockMapper.selectOne(new LambdaQueryWrapper<StorageStock>()
                .eq(StorageStock::getBoxActiveKey, boxNo));
        if (req.getFromCellId() != null) {
            if (req.getFromCellId().equals(toCell.getId())) {
                throw new IllegalArgumentException("原位与目的位不能相同: " + toCell.getCellNo());
            }
            fromCell = requireCell(req.getFromCellId());
            if (sourceStock == null || !req.getFromCellId().equals(sourceStock.getCellId())) {
                throw new IllegalArgumentException("原位移出失败：格位 " + fromCell.getCellNo()
                        + " 无箱号 " + boxNo + " 在库");
            }
        } else if (sourceStock != null) {
            throw new IllegalArgumentException("箱号 " + boxNo + " 已在库于其他格位，移动必须登记原位");
        }

        // 消费预约：仅 RESERVED 可转 CONSUMED（条件更新，与超时释放扫描互斥）
        if (reservationMapper.consumeIfReserved(reservation.getId()) == 0) {
            StorageReservation current = reservationMapper.selectById(reservation.getId());
            throw new IllegalArgumentException(RESV_RELEASED.equals(current.getStatus())
                    ? "预约已释放，不能开始移动: " + current.getReserveNo()
                    : "预约已随其他移动单开始，不能重复使用: " + current.getReserveNo());
        }

        LocalDateTime now = LocalDateTime.now();
        StorageMove move = new StorageMove();
        move.setMoveNo("MV-" + String.format("%06d", moveMapper.nextMoveNo()));
        move.setRequestId(req.getRequestId());
        move.setReservationId(reservation.getId());
        move.setBoxNo(boxNo);
        move.setFromCellId(req.getFromCellId());
        move.setToCellId(toCell.getId());
        move.setOperator(req.getOperator());
        move.setStatus(MOVE_MOVING);
        move.setStartedAt(now);
        moveMapper.insert(move);

        // 原位移出：在库箱随移动开始离架（条件更新，并发安全）
        if (sourceStock != null
                && stockMapper.checkoutIfInStock(sourceStock.getId(), move.getId(), now) == 0) {
            throw new IllegalStateException("原位库存已被其他移动取走: " + boxNo);
        }

        // 互斥设备时段锁：源/目的密集架各占一行，同一时段只允许一个操作
        Set<Long> shelfIds = new LinkedHashSet<>();
        if (fromCell != null && fromCell.getShelfId() != null) {
            shelfIds.add(fromCell.getShelfId());
        }
        if (toCell.getShelfId() != null) {
            shelfIds.add(toCell.getShelfId());
        }
        for (Long shelfId : shelfIds) {
            CompactShelf shelf = requireShelf(shelfId);
            if (lockMapper.selectCount(new LambdaQueryWrapper<StorageDeviceLock>()
                    .eq(StorageDeviceLock::getActiveKey, shelfId)) > 0) {
                throw new IllegalArgumentException("互斥设备同一时段只允许一个操作：密集架 "
                        + shelf.getShelfNo() + " 正在操作中");
            }
            StorageDeviceLock lock = new StorageDeviceLock();
            lock.setShelfId(shelfId);
            lock.setMoveId(move.getId());
            lock.setStatus(LOCK_ACTIVE);
            lock.setActiveKey(shelfId);
            lock.setLockedAt(now);
            lockMapper.insert(lock);
        }

        ledgerService.append(toCell.getId(), reservation.getId(), move.getId(), boxNo,
                ACTION_MOVE_START, move.getMoveNo(),
                "移动开始：箱号 " + boxNo + "，原位 "
                        + (fromCell == null ? "（外部搬入）" : fromCell.getCellNo())
                        + "，目的位 " + toCell.getCellNo(),
                req.getOperator(), now);
        return new MoveOutcome(move, true);
    }

    /** 移动完成：库存落目的位、预约占用键交棒、释放互斥设备时段锁 */
    @Transactional
    public StorageMove completeMove(Long moveId, MoveCompleteRequest req) {
        StorageMove move = moveMapper.selectById(moveId);
        if (move == null) {
            throw new IllegalArgumentException("移动单不存在: id=" + moveId);
        }
        if (MOVE_DONE.equals(move.getStatus())) {
            throw new IllegalArgumentException("移动单已完成，不能重复完成: " + move.getMoveNo());
        }
        StorageCell toCell = requireCell(move.getToCellId());
        LocalDateTime now = LocalDateTime.now();

        StorageStock stock = new StorageStock();
        stock.setCellId(toCell.getId());
        stock.setBoxNo(move.getBoxNo());
        stock.setMoveId(move.getId());
        stock.setOperator(move.getOperator());
        stock.setStatus(STOCK_IN_STOCK);
        stock.setCellActiveKey(toCell.getId());
        stock.setBoxActiveKey(move.getBoxNo());
        stock.setInAt(now);
        stockMapper.insert(stock);

        // 预约占用键交棒给库存行（同事务，格位占用无空窗）
        reservationMapper.clearActiveKeys(move.getReservationId());
        // 释放互斥设备时段锁
        lockMapper.releaseByMove(move.getId(), now);

        move.setStatus(MOVE_DONE);
        move.setFinishedAt(now);
        move.setFinishedBy(req.getOperator());
        moveMapper.updateById(move);

        ledgerService.append(toCell.getId(), move.getReservationId(), move.getId(), move.getBoxNo(),
                ACTION_MOVE_COMPLETE, move.getMoveNo(),
                "移动完成：箱号 " + move.getBoxNo() + " 落位 " + toCell.getCellNo()
                        + "，互斥设备时段锁已释放",
                req.getOperator(), now);
        return move;
    }

    public List<StorageMove> listMoves(String boxNo) {
        return moveMapper.selectList(new LambdaQueryWrapper<StorageMove>()
                .eq(boxNo != null && !boxNo.isEmpty(), StorageMove::getBoxNo, boxNo)
                .orderByAsc(StorageMove::getId));
    }

    /** 移动单详情：移动单 + 关联预约 + 互斥设备时段锁 */
    public Map<String, Object> moveDetail(Long moveId) {
        StorageMove move = moveMapper.selectById(moveId);
        if (move == null) {
            throw new IllegalArgumentException("移动单不存在: id=" + moveId);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("move", move);
        detail.put("reservation", reservationMapper.selectById(move.getReservationId()));
        detail.put("deviceLocks", lockMapper.selectList(new LambdaQueryWrapper<StorageDeviceLock>()
                .eq(StorageDeviceLock::getMoveId, moveId)
                .orderByAsc(StorageDeviceLock::getId)));
        return detail;
    }

    private StorageMove findMoveByRequestId(String requestId) {
        return moveMapper.selectOne(new LambdaQueryWrapper<StorageMove>()
                .eq(StorageMove::getRequestId, requestId));
    }

    // ================================================================
    // 库存查询：按馆员权限隐藏敏感位置
    // ================================================================

    /**
     * 库存查询（仅在库箱）。CURATOR 全量可见；RESTORER 敏感位置脱敏（位置标识
     * 掩码为 ***）；VIEWER 及未声明岗位完全看不到敏感位置。
     */
    public List<Map<String, Object>> stockQuery(String role, Long cellId, String boxNo) {
        List<StorageStock> stocks = stockMapper.selectList(new LambdaQueryWrapper<StorageStock>()
                .eq(StorageStock::getStatus, STOCK_IN_STOCK)
                .eq(cellId != null, StorageStock::getCellId, cellId)
                .eq(boxNo != null && !boxNo.isEmpty(), StorageStock::getBoxNo, boxNo)
                .orderByAsc(StorageStock::getId));
        Map<Long, String> shelfNos = shelfNos();
        List<Map<String, Object>> result = new ArrayList<>();
        for (StorageStock stock : stocks) {
            StorageCell cell = cellMapper.selectById(stock.getCellId());
            if (cell == null) {
                continue;
            }
            Map<String, Object> view = cellView(cell, shelfNos.get(cell.getShelfId()), role);
            if (view == null) {
                continue;
            }
            view.put("stockId", stock.getId());
            view.put("boxNo", stock.getBoxNo());
            view.put("operator", stock.getOperator());
            view.put("inAt", stock.getInAt());
            result.add(view);
        }
        return result;
    }

    // ================================================================
    // 脱敏与校验工具
    // ================================================================

    /**
     * 库位视图（含所属密集架编号）。敏感位置：CURATOR 全量可见；RESTORER 位置
     * 标识（库位编号/密集架编号/位置说明）掩码为 ***；其余岗位（VIEWER 等）
     * 返回 null 表示整行隐藏。
     */
    private Map<String, Object> cellView(StorageCell cell, String shelfNo, String role) {
        boolean sensitive = cell.getSensitive() != null && cell.getSensitive() == 1;
        String normalized = role == null || role.isEmpty() ? ROLE_VIEWER : role;
        if (sensitive && !ROLE_CURATOR.equals(normalized) && !ROLE_RESTORER.equals(normalized)) {
            return null;
        }
        boolean mask = sensitive && ROLE_RESTORER.equals(normalized);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("cellId", cell.getId());
        view.put("cellNo", mask ? MASK : cell.getCellNo());
        view.put("shelfId", cell.getShelfId());
        view.put("shelfNo", mask && shelfNo != null ? MASK : shelfNo);
        view.put("zone", cell.getZone());
        view.put("fireRating", cell.getFireRating());
        view.put("loadCapacityKg", cell.getLoadCapacityKg());
        view.put("sensitive", sensitive ? 1 : 0);
        view.put("location", mask ? MASK : cell.getLocation());
        view.put("status", cell.getStatus());
        return view;
    }

    private Map<Long, String> shelfNos() {
        return listShelves().stream().collect(Collectors.toMap(
                CompactShelf::getId, CompactShelf::getShelfNo));
    }

    private void validateZone(String zone) {
        if (!ZONE_NORMAL.equals(zone) && !ZONE_CONSTANT.equals(zone) && !ZONE_LOW_TEMP.equals(zone)) {
            throw new IllegalArgumentException("温区非法: " + zone + "（可选 NORMAL/CONSTANT/LOW_TEMP）");
        }
    }

    private void validateFireRating(String fireRating) {
        if (!FIRE_F1.equals(fireRating) && !FIRE_F2.equals(fireRating) && !FIRE_F3.equals(fireRating)) {
            throw new IllegalArgumentException("防火等级非法: " + fireRating + "（可选 F1/F2/F3）");
        }
    }

    /** 预约结果：created=false 表示同 requestId 的补传/补录/重试，回读既有预约 */
    public static class ReserveOutcome {
        private final StorageReservation reservation;
        private final boolean created;

        ReserveOutcome(StorageReservation reservation, boolean created) {
            this.reservation = reservation;
            this.created = created;
        }

        public StorageReservation getReservation() {
            return reservation;
        }

        public boolean isCreated() {
            return created;
        }
    }

    /** 移动单登记结果：created=false 表示同 requestId 的重试，回读既有移动单 */
    public static class MoveOutcome {
        private final StorageMove move;
        private final boolean created;

        MoveOutcome(StorageMove move, boolean created) {
            this.move = move;
            this.created = created;
        }

        public StorageMove getMove() {
            return move;
        }

        public boolean isCreated() {
            return created;
        }
    }
}
