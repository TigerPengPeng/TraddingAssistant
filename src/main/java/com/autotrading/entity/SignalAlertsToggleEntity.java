package com.autotrading.entity;

import jakarta.persistence.*;

/**
 * Persisted on/off switch for trading-signal alert emails. Singleton row (id always 1).
 * <p>
 * When {@code enabled=false}, {@link com.autotrading.monitor.TradingSignalScanner}
 * still detects signals and records them (dashboard + DB, marked suppressed),
 * but does not send the email alert. {@link com.autotrading.monitor.SignalAlertsToggle}
 * caches this in memory on startup and writes through on change, so reads on the
 * scan path never hit the database.
 */
@Entity
@Table(name = "signal_alerts_toggle")
public class SignalAlertsToggleEntity {

    /** Singleton primary key (always 1). */
    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private boolean enabled = true;

    public SignalAlertsToggleEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
