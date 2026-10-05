package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.CellCreateRequest;
import com.evops.dto.CellUpdateRequest;
import com.evops.dto.ShelfCreateRequest;
import com.evops.entity.CompactShelf;
import com.evops.entity.StorageCell;
import com.evops.entity.StorageLedgerEntry;
import com.evops.service.StorageLedgerService;
import com.evops.service.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 库房库位与密集架：库位按温区、防火等级、承重维护；
 * 库存查询按馆员权限隐藏敏感位置。
 */
@RestController
public class StorageController {

    private final StorageService storageService;
    private final StorageLedgerService ledgerService;

    public StorageController(StorageService storageService, StorageLedgerService ledgerService) {
        this.storageService = storageService;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/api/storage/shelves")
    public ApiResponse<CompactShelf> createShelf(@Valid @RequestBody ShelfCreateRequest request) {
        return ApiResponse.ok(storageService.createShelf(request));
    }

    @GetMapping("/api/storage/shelves")
    public ApiResponse<List<CompactShelf>> listShelves() {
        return ApiResponse.ok(storageService.listShelves());
    }

    @PostMapping("/api/storage/cells")
    public ApiResponse<StorageCell> createCell(@Valid @RequestBody CellCreateRequest request) {
        return ApiResponse.ok(storageService.createCell(request));
    }

    /** 库位维护：温区/防火等级/承重/敏感标记/停用 */
    @PutMapping("/api/storage/cells/{id}")
    public ApiResponse<StorageCell> updateCell(@PathVariable Long id,
                                               @Valid @RequestBody CellUpdateRequest request) {
        return ApiResponse.ok(storageService.updateCell(id, request));
    }

    /** 库位列表：敏感位置按岗位脱敏（RESTORER）或隐藏（VIEWER） */
    @GetMapping("/api/storage/cells")
    public ApiResponse<List<Map<String, Object>>> listCells(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String fireRating,
            @RequestParam(required = false) Long shelfId) {
        return ApiResponse.ok(storageService.listCells(role, zone, fireRating, shelfId));
    }

    /** 库存查询：按馆员权限隐藏敏感位置 */
    @GetMapping("/api/storage/stock")
    public ApiResponse<List<Map<String, Object>>> stockQuery(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long cellId,
            @RequestParam(required = false) String boxNo) {
        return ApiResponse.ok(storageService.stockQuery(role, cellId, boxNo));
    }

    /** 库房台账：建档/维护/预约/释放/移动全程留痕 */
    @GetMapping("/api/storage/ledgers")
    public ApiResponse<List<StorageLedgerEntry>> ledgers(
            @RequestParam(required = false) Long cellId,
            @RequestParam(required = false) String boxNo) {
        return ApiResponse.ok(ledgerService.list(cellId, boxNo));
    }
}
