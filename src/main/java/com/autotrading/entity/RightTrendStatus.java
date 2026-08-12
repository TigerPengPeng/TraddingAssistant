package com.autotrading.entity;

/**
 * 右侧趋势分析任务的状态机。
 *
 * <pre>
 *   QUEUED ──→ FETCHING ──→ ANALYZING ──→ DONE              ✅终态
 *                │             │
 *                ├─→ STALE     ├─→ ANALYSIS_FAILED          ⚠️可补偿
 *                ├─→ RATE_LIMITED
 *                └─→ FAILED                                 ❌终态
 *
 *   补偿循环扫 STALE / RATE_LIMITED / ANALYSIS_FAILED → 重跑 → 成功 DONE
 *   STALE 超 staleTtlDays 未成功 → FAILED
 *   同 tradeDate 新记录写入 → 旧记录置 SUPERSEDED
 * </pre>
 */
public enum RightTrendStatus {
    QUEUED,            // 入队待分析
    FETCHING,          // 拉 K 线中（瞬时态，实际不长期留存）
    ANALYZING,         // 调 LLM 中（瞬时态）
    STALE,             // K 线最新 bar 陈旧（可补偿，跨天）
    RATE_LIMITED,      // OpenD 限流（可补偿）
    ANALYSIS_FAILED,   // LLM 调用失败（可补偿）
    FAILED,            // 不可恢复（无权限/不支持/STALE 超期）— 终态
    DONE,              // 成功 — 终态
    SUPERSEDED;        // 同 tradeDate 被更新记录取代 — 终态

    /** 可被补偿调度器重试的状态。 */
    public boolean isCompensable() {
        return this == STALE || this == RATE_LIMITED || this == ANALYSIS_FAILED;
    }

    /** 终态（不再变化，补偿器不再扫）。 */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == SUPERSEDED;
    }
}
