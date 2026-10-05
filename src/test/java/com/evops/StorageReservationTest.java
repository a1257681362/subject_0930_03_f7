package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物库房库位与密集架预约（museum-7）验收：
 * 1) 库位按温区/防火等级/承重建档与维护，维护写台账；
 * 2) 搬入前必须预约目标格位；承重超限、温区/防火等级不匹配、格位占用一律拒绝；
 * 3) 补传/补录/重试（同 requestId）只形成一条可用结果；
 * 4) 移动单记录原位/目的位/箱号/操作人员；互斥设备同一时段只允许一个操作；
 * 5) 预约超时自动释放（扫描幂等），已开始移动的格位绝不释放；
 * 6) 库存查询按馆员权限隐藏敏感位置（CURATOR 全见 / RESTORER 脱敏 / VIEWER 隐藏）。
 * 使用独立内存库，不调用起步接口；后台扫描关闭，释放只走手动触发，时序确定。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:museum-storage;DB_CLOSE_DELAY=-1",
        "museum.storage.release-scan-on-startup=false",
        "museum.storage.release-scan-interval-ms=600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageReservationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void storage_reservation_rules_hold() throws Exception {
        // ========== 一、密集架与库位建档：按温区/防火等级/承重维护 ==========
        long shelf1 = dataOf(postJson("/api/storage/shelves",
                "{\"shelfNo\":\"MJ-01\",\"name\":\"一号密集架\",\"location\":\"一层库房\"}"))
                .get("id").asLong();
        long shelf2 = dataOf(postJson("/api/storage/shelves",
                "{\"shelfNo\":\"MJ-02\",\"name\":\"二号密集架\",\"location\":\"一层库房\"}"))
                .get("id").asLong();
        expectFail(postJson("/api/storage/shelves",
                "{\"shelfNo\":\"MJ-01\",\"name\":\"重复编号\"}"), "密集架编号已存在");

        // C1 挂 MJ-01（恒温/F1/承重100），C2 挂 MJ-01（敏感位置），C3 挂 MJ-02，
        // C4/C5 普通库位（不挂密集架）
        long cell1 = createCell("KW-A-01", shelf1, "CONSTANT", "F1", 100, 0);
        long cell2 = createCell("KW-A-02", shelf1, "NORMAL", "F2", 50, 1);
        long cell3 = createCell("KW-B-01", shelf2, "NORMAL", "F2", 30, 0);
        long cell4 = createCell("KW-C-01", null, "LOW_TEMP", "F3", 20, 0);
        long cell5 = createCell("KW-C-02", null, "NORMAL", "F2", 100, 0);
        expectFail(postJson("/api/storage/cells", "{"
                + "\"cellNo\":\"KW-A-01\",\"zone\":\"NORMAL\",\"fireRating\":\"F1\","
                + "\"loadCapacityKg\":10}"), "库位编号已存在");
        expectFail(postJson("/api/storage/cells", "{"
                + "\"cellNo\":\"KW-X-01\",\"zone\":\"HOT\",\"fireRating\":\"F1\","
                + "\"loadCapacityKg\":10}"), "温区非法");

        // 库位维护：温区/防火等级/承重可维护并写台账
        expectOk(putJson("/api/storage/cells/" + cell1, "{"
                + "\"zone\":\"LOW_TEMP\",\"fireRating\":\"F2\",\"loadCapacityKg\":120,"
                + "\"operator\":\"库管员王\"}"));
        JsonNode cell1View = findCell(cells("role=CURATOR"), cell1);
        assertEquals("LOW_TEMP", cell1View.get("zone").asText());
        assertEquals("F2", cell1View.get("fireRating").asText());
        assertEquals(120, cell1View.get("loadCapacityKg").asInt());
        assertTrue(hasAction(ledgers(null, null), "CELL_UPDATE"), "库位维护应写台账");

        // ========== 二、预约校验：承重 / 温区 / 防火等级 ==========
        expectFail(reserve("RQ-W1", cell1, "BOX-W1", 150, null, null, 30), "超过库位承重");
        expectFail(reserve("RQ-Z1", cell1, "BOX-Z1", 50, "CONSTANT", null, 30), "温区不匹配");
        expectFail(reserve("RQ-F1", cell1, "BOX-F1", 50, null, "F1", 30), "防火等级不匹配");

        // ========== 三、预约成功 + 补传/补录/重试只形成一条可用结果 ==========
        JsonNode r1 = dataOf(reserve("RQ-0001", cell1, "BOX-001", 50, "LOW_TEMP", "F2", 30));
        assertTrue(r1.get("created").asBoolean());
        long resv1 = r1.get("reservation").get("id").asLong();
        assertEquals("RESERVED", r1.get("reservation").get("status").asText());
        assertNotNull(r1.get("reservation").get("expireAt"));

        // 同 requestId 重试（补传/补录）：回读同一条，不产生第二条
        JsonNode r1Retry = dataOf(reserve("RQ-0001", cell1, "BOX-001", 50, null, null, 30));
        assertFalse(r1Retry.get("created").asBoolean());
        assertEquals(resv1, r1Retry.get("reservation").get("id").asLong());
        assertEquals(1, countReservationsForCell(cell1));

        // 不同 requestId 预约同一格位：拒绝（一格一条有效预约）
        expectFail(reserve("RQ-0002", cell1, "BOX-002", 40, null, null, 30), "格位已有有效预约");
        // 同一箱号另约其他格位：拒绝（一箱一条在途预约）
        expectFail(reserve("RQ-0003", cell3, "BOX-001", 20, null, null, 30), "箱号已有在途预约");

        // ========== 四、移动单：记录原位/目的位/箱号/操作人员 ==========
        // 未预约直接移动：预约不存在拒绝
        expectFail(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-M0\",\"reservationId\":999999,\"operator\":\"张三\"}"),
                "预约不存在");
        // 外部搬入（无原位）开始移动
        JsonNode m1 = dataOf(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-M1\",\"reservationId\":" + resv1 + ",\"operator\":\"张三\"}"));
        assertTrue(m1.get("created").asBoolean());
        long move1 = m1.get("move").get("id").asLong();
        assertEquals("MOVING", m1.get("move").get("status").asText());
        assertEquals("BOX-001", m1.get("move").get("boxNo").asText());
        assertEquals(cell1, m1.get("move").get("toCellId").asLong());
        assertEquals("张三", m1.get("move").get("operator").asText());
        // 移动单重试（同 requestId）：回读同一条
        JsonNode m1Retry = dataOf(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-M1\",\"reservationId\":" + resv1 + ",\"operator\":\"张三\"}"));
        assertFalse(m1Retry.get("created").asBoolean());
        assertEquals(move1, m1Retry.get("move").get("id").asLong());
        // 预约已消费，换请求号重复移动同一预约：拒绝
        expectFail(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-M1B\",\"reservationId\":" + resv1 + ",\"operator\":\"李四\"}"),
                "已随其他移动单开始");

        // ========== 五、互斥设备同一时段只允许一个操作 ==========
        // C2 与 C1 同挂 MJ-01：移动进行中，同架第二个操作拒绝
        long resv2 = dataOf(reserve("RQ-0004", cell2, "BOX-002", 30, null, null, 30))
                .get("reservation").get("id").asLong();
        expectFail(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-M2\",\"reservationId\":" + resv2 + ",\"operator\":\"李四\"}"),
                "同一时段只允许一个操作");
        // 不同密集架（C3 挂 MJ-02）不受互斥影响
        long resv3 = dataOf(reserve("RQ-0005", cell3, "BOX-003", 20, null, null, 30))
                .get("reservation").get("id").asLong();
        JsonNode m3 = dataOf(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-M3\",\"reservationId\":" + resv3 + ",\"operator\":\"王五\"}"));
        assertTrue(m3.get("created").asBoolean());
        long move3 = m3.get("move").get("id").asLong();

        // 完成 move1（落位 C1）后 MJ-01 时段锁释放，C2 移动放行
        expectOk(postJson("/api/storage/moves/" + move1 + "/complete", "{\"operator\":\"赵六\"}"));
        expectFail(postJson("/api/storage/moves/" + move1 + "/complete",
                "{\"operator\":\"赵六\"}"), "已完成");
        JsonNode m2 = dataOf(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-M2\",\"reservationId\":" + resv2 + ",\"operator\":\"李四\"}"));
        assertTrue(m2.get("created").asBoolean());
        long move2 = m2.get("move").get("id").asLong();
        // 时段锁随移动单留痕：move1 的锁已释放，move2 的锁占用中
        JsonNode m1Detail = dataOf(getJson("/api/storage/moves/" + move1));
        assertEquals("RELEASED", m1Detail.get("deviceLocks").get(0).get("status").asText());
        JsonNode m2Detail = dataOf(getJson("/api/storage/moves/" + move2));
        assertEquals("ACTIVE", m2Detail.get("deviceLocks").get(0).get("status").asText());
        expectOk(postJson("/api/storage/moves/" + move2 + "/complete", "{\"operator\":\"赵六\"}"));
        expectOk(postJson("/api/storage/moves/" + move3 + "/complete", "{\"operator\":\"赵六\"}"));

        // 库存落位：BOX-001@C1、BOX-002@C2、BOX-003@C3
        JsonNode stockCurator = dataOf(getJson("/api/storage/stock?role=CURATOR"));
        assertEquals(3, stockCurator.size());
        assertEquals("BOX-001", findStock(stockCurator, cell1).get("boxNo").asText());

        // ========== 六、库存查询按馆员权限隐藏敏感位置 ==========
        // C2 为敏感位置：CURATOR 全见；RESTORER 位置脱敏；VIEWER/未声明不可见
        JsonNode restorerStock = dataOf(getJson("/api/storage/stock?role=RESTORER"));
        assertEquals(3, restorerStock.size());
        JsonNode masked = findStock(restorerStock, cell2);
        assertEquals("***", masked.get("cellNo").asText());
        assertEquals("***", masked.get("shelfNo").asText());
        assertEquals("BOX-002", masked.get("boxNo").asText(), "修复师可见箱号但位置脱敏");
        JsonNode viewerStock = dataOf(getJson("/api/storage/stock?role=VIEWER"));
        assertEquals(2, viewerStock.size());
        assertNull(findStockOrNull(viewerStock, cell2), "访客不应看到敏感位置");
        JsonNode anonStock = dataOf(getJson("/api/storage/stock"));
        assertEquals(2, anonStock.size(), "未声明岗位按访客口径隐藏敏感位置");
        // 库位列表同一口径
        assertNull(findCellOrNull(cells("role=VIEWER"), cell2));
        assertEquals("***", findCell(cells("role=RESTORER"), cell2).get("cellNo").asText());
        assertEquals("KW-A-02", findCell(cells("role=CURATOR"), cell2).get("cellNo").asText());

        // ========== 七、预约超时自动释放（扫描幂等） ==========
        // ttl=0 立即超时；扫描释放并写台账，重复扫描不多写
        long resvTimeout = dataOf(reserve("RQ-T1", cell4, "BOX-T1", 10, null, null, 0))
                .get("reservation").get("id").asLong();
        assertEquals(1, dataOf(postEmpty("/api/storage/reservations/release-expired"))
                .get("released").asInt());
        JsonNode releasedView = findReservation(resvTimeout);
        assertEquals("RELEASED", releasedView.get("status").asText());
        assertEquals("TIMEOUT", releasedView.get("releasedReason").asText());
        assertEquals(1, countAction(ledgers(null, null), "RESERVE_RELEASE"), "超时释放应写一条台账");
        assertEquals(0, dataOf(postEmpty("/api/storage/reservations/release-expired"))
                .get("released").asInt(), "重复扫描不应再释放（幂等）");
        assertEquals(1, countAction(ledgers(null, null), "RESERVE_RELEASE"), "重复扫描不多写台账");
        // 释放后格位可再预约（ttl=0 立即超时，留给下一步宽限消费）
        JsonNode r2 = dataOf(reserve("RQ-T2", cell4, "BOX-T2", 10, null, null, 0));
        assertTrue(r2.get("created").asBoolean());
        long resvGrace = r2.get("reservation").get("id").asLong();

        // ========== 八、已开始移动的格位绝不释放 ==========
        // 已超时但尚未被扫描释放的预约仍可开始移动（宽限）；移动开始后扫描不得释放
        JsonNode mGrace = dataOf(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-MG\",\"reservationId\":" + resvGrace
                        + ",\"operator\":\"张三\"}"));
        assertTrue(mGrace.get("created").asBoolean());
        long moveGrace = mGrace.get("move").get("id").asLong();
        // 扫描：CONSUMED 预约（移动进行中）绝不释放
        assertEquals(0, dataOf(postEmpty("/api/storage/reservations/release-expired"))
                .get("released").asInt());
        assertEquals("CONSUMED", findReservation(resvGrace).get("status").asText());
        // 完成移动后库存落位，预约占用键交棒
        expectOk(postJson("/api/storage/moves/" + moveGrace + "/complete",
                "{\"operator\":\"赵六\"}"));
        assertEquals("BOX-T2", findStock(dataOf(getJson("/api/storage/stock?role=CURATOR")), cell4)
                .get("boxNo").asText());

        // ========== 九、带原位的库间移动：原位/目的位/箱号/操作人员全留痕 ==========
        // BOX-001 在 C1；预约 C5 为目的位（普通库位，不挂密集架）
        long resvMove = dataOf(reserve("RQ-MV1", cell5, "BOX-001", 50, null, null, 30))
                .get("reservation").get("id").asLong();
        // 原位与目的位相同拒绝
        expectFail(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-MV0\",\"reservationId\":" + resvMove
                        + ",\"fromCellId\":" + cell5 + ",\"operator\":\"张三\"}"),
                "原位与目的位不能相同");
        // 箱在库于其他格位时不登记原位拒绝
        expectFail(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-MV1X\",\"reservationId\":" + resvMove
                        + ",\"operator\":\"张三\"}"),
                "必须登记原位");
        // 原位登记错误（BOX-001 在 C1 不在 C4）拒绝
        expectFail(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-MV1Y\",\"reservationId\":" + resvMove
                        + ",\"fromCellId\":" + cell4 + ",\"operator\":\"张三\"}"),
                "无箱号 BOX-001 在库");
        JsonNode moveC1C5 = dataOf(postJson("/api/storage/moves",
                "{\"requestId\":\"RQ-MV1\",\"reservationId\":" + resvMove
                        + ",\"fromCellId\":" + cell1 + ",\"operator\":\"张三\"}"));
        long moveC1C5Id = moveC1C5.get("move").get("id").asLong();
        assertEquals(cell1, moveC1C5.get("move").get("fromCellId").asLong());
        assertEquals(cell5, moveC1C5.get("move").get("toCellId").asLong());
        assertEquals("BOX-001", moveC1C5.get("move").get("boxNo").asText());
        assertEquals("张三", moveC1C5.get("move").get("operator").asText());
        // 移动开始即原位移出：C1 不再有 BOX-001
        assertNull(findStockOrNull(
                dataOf(getJson("/api/storage/stock?role=CURATOR&cellId=" + cell1)), "BOX-001"));
        expectOk(postJson("/api/storage/moves/" + moveC1C5Id + "/complete",
                "{\"operator\":\"赵六\"}"));
        JsonNode finalStock = dataOf(getJson("/api/storage/stock?role=CURATOR"));
        assertEquals("BOX-001", findStock(finalStock, cell5).get("boxNo").asText());
        // 一箱一位：BOX-001 仅在 C5 在库
        assertEquals(1, countStockBox(finalStock, "BOX-001"));

        // ========== 十、台账全程留痕 ==========
        JsonNode ledgerAll = ledgers(null, null);
        assertTrue(hasAction(ledgerAll, "SHELF_CREATE"));
        assertTrue(hasAction(ledgerAll, "CELL_CREATE"));
        assertTrue(hasAction(ledgerAll, "CELL_UPDATE"));
        assertTrue(hasAction(ledgerAll, "RESERVE"));
        assertTrue(hasAction(ledgerAll, "RESERVE_RELEASE"));
        assertTrue(hasAction(ledgerAll, "MOVE_START"));
        assertTrue(hasAction(ledgerAll, "MOVE_COMPLETE"));
        // 箱号维度可追溯：BOX-001 的预约/移动/落位链完整
        JsonNode boxLedger = ledgers(null, "BOX-001");
        assertTrue(hasAction(boxLedger, "RESERVE"));
        assertTrue(hasAction(boxLedger, "MOVE_START"));
        assertTrue(hasAction(boxLedger, "MOVE_COMPLETE"));
    }

    // ----------------------------------------------------------------
    // 请求与断言工具
    // ----------------------------------------------------------------

    private long createCell(String cellNo, Long shelfId, String zone, String fireRating,
                            int loadKg, int sensitive) throws Exception {
        StringBuilder body = new StringBuilder("{");
        body.append("\"cellNo\":\"").append(cellNo).append("\",");
        if (shelfId != null) {
            body.append("\"shelfId\":").append(shelfId).append(",");
        }
        body.append("\"zone\":\"").append(zone).append("\",")
                .append("\"fireRating\":\"").append(fireRating).append("\",")
                .append("\"loadCapacityKg\":").append(loadKg).append(",")
                .append("\"sensitive\":").append(sensitive).append("}");
        return dataOf(postJson("/api/storage/cells", body.toString())).get("id").asLong();
    }

    private MvcResult reserve(String requestId, long cellId, String boxNo, int weightKg,
                              String requiredZone, String requiredFireRating, int ttlMinutes)
            throws Exception {
        StringBuilder body = new StringBuilder("{");
        body.append("\"requestId\":\"").append(requestId).append("\",")
                .append("\"cellId\":").append(cellId).append(",")
                .append("\"boxNo\":\"").append(boxNo).append("\",")
                .append("\"boxWeightKg\":").append(weightKg).append(",");
        if (requiredZone != null) {
            body.append("\"requiredZone\":\"").append(requiredZone).append("\",");
        }
        if (requiredFireRating != null) {
            body.append("\"requiredFireRating\":\"").append(requiredFireRating).append("\",");
        }
        body.append("\"ttlMinutes\":").append(ttlMinutes).append(",")
                .append("\"operator\":\"库管员王\"}");
        return postJson("/api/storage/reservations", body.toString());
    }

    private JsonNode cells(String query) throws Exception {
        return dataOf(getJson("/api/storage/cells?" + query));
    }

    private JsonNode findCell(JsonNode cells, long cellId) {
        JsonNode found = findCellOrNull(cells, cellId);
        if (found == null) {
            throw new AssertionError("库位未找到: " + cellId);
        }
        return found;
    }

    private JsonNode findCellOrNull(JsonNode cells, long cellId) {
        for (JsonNode c : cells) {
            if (c.get("cellId").asLong() == cellId) {
                return c;
            }
        }
        return null;
    }

    private JsonNode findStock(JsonNode stock, long cellId) {
        JsonNode found = findStockOrNull(stock, cellId);
        if (found == null) {
            throw new AssertionError("格位库存未找到: " + cellId);
        }
        return found;
    }

    private JsonNode findStockOrNull(JsonNode stock, long cellId) {
        for (JsonNode s : stock) {
            if (s.get("cellId").asLong() == cellId) {
                return s;
            }
        }
        return null;
    }

    private JsonNode findStockOrNull(JsonNode stock, String boxNo) {
        for (JsonNode s : stock) {
            if (boxNo.equals(s.get("boxNo").asText())) {
                return s;
            }
        }
        return null;
    }

    private int countStockBox(JsonNode stock, String boxNo) {
        int count = 0;
        for (JsonNode s : stock) {
            if (boxNo.equals(s.get("boxNo").asText())) {
                count++;
            }
        }
        return count;
    }

    private int countReservationsForCell(long cellId) throws Exception {
        int count = 0;
        for (JsonNode r : dataOf(getJson("/api/storage/reservations"))) {
            if (r.get("cellId").asLong() == cellId) {
                count++;
            }
        }
        return count;
    }

    private JsonNode findReservation(long reservationId) throws Exception {
        for (JsonNode r : dataOf(getJson("/api/storage/reservations"))) {
            if (r.get("id").asLong() == reservationId) {
                return r;
            }
        }
        throw new AssertionError("预约未找到: " + reservationId);
    }

    private JsonNode ledgers(Long cellId, String boxNo) throws Exception {
        StringBuilder uri = new StringBuilder("/api/storage/ledgers?1=1");
        if (cellId != null) {
            uri.append("&cellId=").append(cellId);
        }
        if (boxNo != null) {
            uri.append("&boxNo=").append(boxNo);
        }
        return dataOf(getJson(uri.toString()));
    }

    private boolean hasAction(JsonNode ledger, String action) {
        return countAction(ledger, action) > 0;
    }

    private int countAction(JsonNode ledger, String action) {
        int count = 0;
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                count++;
            }
        }
        return count;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult putJson(String uri, String body) throws Exception {
        return mockMvc.perform(put(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
