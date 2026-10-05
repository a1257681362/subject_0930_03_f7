package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.MoveCompleteRequest;
import com.evops.dto.MoveStartRequest;
import com.evops.dto.ReservationRequest;
import com.evops.entity.StorageMove;
import com.evops.entity.StorageReservation;
import com.evops.service.StorageReleaseScheduler;
import com.evops.service.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 格位预约与移动单：搬入前必须预约目标格位；预约超时自动释放
 * （已开始移动的格位不释放）；补传/补录/重试凭 requestId 只形成一条可用结果。
 */
@RestController
public class StorageMoveController {

    private final StorageService storageService;
    private final StorageReleaseScheduler releaseScheduler;

    public StorageMoveController(StorageService storageService,
                                 StorageReleaseScheduler releaseScheduler) {
        this.storageService = storageService;
        this.releaseScheduler = releaseScheduler;
    }

    /** 预约目标格位：同 requestId 重复提交只回读同一条（created=false） */
    @PostMapping("/api/storage/reservations")
    public ApiResponse<Map<String, Object>> reserve(@Valid @RequestBody ReservationRequest request) {
        StorageService.ReserveOutcome outcome = storageService.reserve(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", outcome.isCreated());
        result.put("reservation", outcome.getReservation());
        return ApiResponse.ok(result);
    }

    @GetMapping("/api/storage/reservations")
    public ApiResponse<List<StorageReservation>> listReservations(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(storageService.listReservations(status));
    }

    /** 手动触发一轮超时释放扫描（与定时任务同一口径，幂等） */
    @PostMapping("/api/storage/reservations/release-expired")
    public ApiResponse<Map<String, Object>> releaseExpired() {
        int released = releaseScheduler.scanOnce();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("released", released);
        return ApiResponse.ok(result);
    }

    /** 登记移动单（开始移动）：记录原位/目的位/箱号/操作人员，占用互斥设备时段锁 */
    @PostMapping("/api/storage/moves")
    public ApiResponse<Map<String, Object>> startMove(@Valid @RequestBody MoveStartRequest request) {
        StorageService.MoveOutcome outcome = storageService.startMove(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", outcome.isCreated());
        result.put("move", outcome.getMove());
        return ApiResponse.ok(result);
    }

    @GetMapping("/api/storage/moves")
    public ApiResponse<List<StorageMove>> listMoves(@RequestParam(required = false) String boxNo) {
        return ApiResponse.ok(storageService.listMoves(boxNo));
    }

    /** 移动单详情：移动单 + 关联预约 + 互斥设备时段锁 */
    @GetMapping("/api/storage/moves/{id}")
    public ApiResponse<Map<String, Object>> moveDetail(@PathVariable Long id) {
        return ApiResponse.ok(storageService.moveDetail(id));
    }

    /** 移动完成：库存落目的位，释放互斥设备时段锁 */
    @PostMapping("/api/storage/moves/{id}/complete")
    public ApiResponse<StorageMove> completeMove(@PathVariable Long id,
                                                 @Valid @RequestBody MoveCompleteRequest request) {
        return ApiResponse.ok(storageService.completeMove(id, request));
    }
}
