package com.autotrading.entity;

import jakarta.persistence.*;

/**
 * Persisted master toggle for the alert-rule execution logic (MA crossover /
 * breakdown + fluctuation rules). Singleton row (id always 1).
 * <p>
 * When {@code enabled=false}, the MA rule engine, MA breakdown scanner, and
 * fluctuation scheduler short-circuit and produce no alerts. Trading-signal
 * scanning and the email subsystem are unaffected. {@link com.autotrading.monitor.AlertRulesToggle}
 * caches this in memory on startup and writes through on change, so reads on
 * the hot path (per quote tick) never hit the database.
 */
@Entity
@Table(name = "alert_rule_toggle")
public class AlertRuleToggleEntity {

    /** Singleton primary key (always 1). */
    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private boolean enabled = true;

    public AlertRuleToggleEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
