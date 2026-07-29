package com.autotrading.monitor;

import com.autotrading.entity.AlertRuleToggleEntity;
import com.autotrading.repository.AlertRuleToggleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Master on/off switch for the MA-rule and fluctuation-rule alert logic.
 * <p>
 * DB-backed singleton ({@code alert_rule_toggle} row id=1), cached in a volatile
 * field on startup so the per-tick {@link #isEnabled()} check never touches the
 * database; {@link #setEnabled(boolean)} writes the cache and persists through.
 * <p>
 * When disabled, {@link MACrossoverMonitor#check}, {@link MABreakdownScanner}
 * scans, and {@link FluctuationAlertScheduler#evaluateAndAlert} short-circuit.
 * Trading-signal scanning and email sending are intentionally NOT gated here.
 */
@Component
public class AlertRulesToggle {

    private static final Long CONFIG_ID = 1L;

    private final AlertRuleToggleRepository repository;
    private volatile boolean enabled = true;

    public AlertRulesToggle(AlertRuleToggleRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void loadFromDatabase() {
        AlertRuleToggleEntity entity = repository.findById(CONFIG_ID).orElse(null);
        if (entity == null) {
            // First boot: seed the row with the default (enabled) so it exists going forward.
            AlertRuleToggleEntity seed = new AlertRuleToggleEntity();
            seed.setEnabled(true);
            repository.save(seed);
            enabled = true;
        } else {
            enabled = entity.isEnabled();
        }
    }

    /** Fast read of the cached flag (hot path — called per quote tick). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Update the cache and persist so the state survives restarts. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        AlertRuleToggleEntity entity = repository.findById(CONFIG_ID).orElse(new AlertRuleToggleEntity());
        entity.setEnabled(enabled);
        repository.save(entity);
    }
}
