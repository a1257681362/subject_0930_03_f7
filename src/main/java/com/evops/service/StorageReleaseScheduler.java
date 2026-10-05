package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumStorageProperties;
import com.evops.entity.StorageReservation;
import com.evops.mapper.StorageMoveMapper;
import com.evops.mapper.StorageReservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static com.evops.constant.MuseumConstants.ACTION_RESERVE_RELEASE;
import static com.evops.constant.MuseumConstants.MOVE_MOVING;
import static com.evops.constant.MuseumConstants.RESV_RESERVED;

/**
 * 预约超时自动释放扫描：
 *
 * <p>周期扫描 expire_at 已过且仍 RESERVED 的预约，条件更新置 RELEASED 并清空
 * 占用键、写台账。凭条件更新（仅 RESERVED 可转）实现<b>重启幂等</b>：重复扫描
 * 命中 0 行不写台账，重启重扫不多写记录。</p>
 *
 * <p><b>已开始移动的格位绝不释放</b>：移动开始即把预约消费为 CONSUMED，扫描天然
 * 跳过；扫描侧再做一次在途移动（MOVING）兜底校验，双保险。</p>
 *
 * <p>应用启动就绪后先补扫一轮（重启场景），之后按固定间隔扫描；
 * 单次扫描串行加锁，启动扫描与周期扫描不会并发重入。</p>
 */
@Component
public class StorageReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageReleaseScheduler.class);

    private final StorageReservationMapper reservationMapper;
    private final StorageMoveMapper moveMapper;
    private final StorageLedgerService ledgerService;
    private final MuseumStorageProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public StorageReleaseScheduler(StorageReservationMapper reservationMapper,
                                   StorageMoveMapper moveMapper,
                                   StorageLedgerService ledgerService,
                                   MuseumStorageProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.reservationMapper = reservationMapper;
        this.moveMapper = moveMapper;
        this.ledgerService = ledgerService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 启动就绪补扫：重启后立即释放历史超时预约 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        if (properties.isReleaseScanOnStartup() && startedUp.compareAndSet(false, true)) {
            log.info("库房格位预约：启动补扫超时预约");
            scanOnce();
        }
    }

    /** 周期扫描（fixedDelay：上一轮结束后再计时，任务堆积不会重叠） */
    @Scheduled(fixedDelayString = "${museum.storage.release-scan-interval-ms:60000}")
    public void scanOnSchedule() {
        scanOnce();
    }

    /**
     * 执行一轮扫描，返回本轮释放的预约数。
     * 公开供接口/测试手动触发；加锁保证与定时轮次不重入。
     */
    public int scanOnce() {
        if (!scanLock.tryLock()) {
            log.debug("超时预约释放扫描上一轮尚未结束，本轮跳过");
            return 0;
        }
        try {
            List<StorageReservation> expired = reservationMapper.selectList(
                    new LambdaQueryWrapper<StorageReservation>()
                            .eq(StorageReservation::getStatus, RESV_RESERVED)
                            .lt(StorageReservation::getExpireAt, LocalDateTime.now())
                            .orderByAsc(StorageReservation::getId));
            int released = 0;
            for (StorageReservation reservation : expired) {
                if (releaseOne(reservation)) {
                    released++;
                }
            }
            if (released > 0) {
                log.info("库房格位预约超时释放扫描：释放 {} 条", released);
            }
            return released;
        } finally {
            scanLock.unlock();
        }
    }

    /**
     * 单行独立事务释放 + 台账。条件更新命中才写台账；并发/重启竞态下
     * 命中 0 行直接跳过，不会多写记录。
     */
    private boolean releaseOne(StorageReservation reservation) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            // 兜底校验：已开始移动的格位绝不释放（正常已由 CONSUMED 状态天然排除）
            Long moving = moveMapper.selectCount(new LambdaQueryWrapper<com.evops.entity.StorageMove>()
                    .eq(com.evops.entity.StorageMove::getReservationId, reservation.getId())
                    .eq(com.evops.entity.StorageMove::getStatus, MOVE_MOVING));
            if (moving != null && moving > 0) {
                log.warn("预约 {} 已随移动单开始，跳过超时释放", reservation.getReserveNo());
                return false;
            }
            // 条件更新：仅 RESERVED 可转 RELEASED，命中才写台账（重启重扫幂等）
            if (reservationMapper.releaseIfReserved(reservation.getId(), now) == 0) {
                return false;
            }
            ledgerService.append(reservation.getCellId(), reservation.getId(), null,
                    reservation.getBoxNo(), ACTION_RESERVE_RELEASE, reservation.getReserveNo(),
                    "预约超时自动释放：格位预约 " + reservation.getReserveNo()
                            + "（箱号 " + reservation.getBoxNo() + "）超过 "
                            + reservation.getExpireAt() + " 未开始移动，占用键已清空",
                    "SYSTEM", now);
            return true;
        }));
    }
}
