package com.company.tradejournal.dto.downloads;

/**
 * 页面上展示的券商任务配置：不把步骤和登录态目录暴露给前端，只看名字、地址和步骤数。
 */
public record BrokerConfigView(String name, boolean enabled, String url, int steps, boolean headless) {
}
