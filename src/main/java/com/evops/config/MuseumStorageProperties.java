package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文物库房库位与密集架预约配置（museum.storage.*）。
 *
 * <ul>
 *   <li>{@code reserve-timeout-min}：预约超时时长（分钟），超时未开始移动的预约
 *       由定时扫描自动释放；</li>
 *   <li>{@code release-scan-interval-ms}：超时释放扫描周期（毫秒），扫描按条件更新
 *       幂等，重启重扫不多写台账；</li>
 *   <li>{@code release-scan-on-startup}：应用就绪后是否立即补扫一轮（重启场景）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.storage")
public class MuseumStorageProperties {

    /** 预约超时时长（分钟）：预约创建后超过该时长仍未开始移动即自动释放 */
    private int reserveTimeoutMin = 30;

    /** 超时预约释放定时扫描间隔（毫秒） */
    private long releaseScanIntervalMs = 60_000L;

    /** 应用启动就绪后是否立即补扫一轮超时预约 */
    private boolean releaseScanOnStartup = true;
}
