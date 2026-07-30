package com.autotrading.monitor;

import com.autotrading.entity.SignalAlertsToggleEntity;
import com.autotrading.repository.SignalAlertsToggleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * On/off switch for trading-signal alert emails.
 * <p>
 * DB-backed singleton ({@code signal_alerts_toggle} row id=1), cached in a volatile
 * field on startup so the per-scan {@link #isEnabled()} check never touches the
 * database; {@link #setEnabled(boolean)} writes the cache and persists through.
 * <p>
 * When disabled, {@link TradingSignalScanner} still detects and records signals
 * (dashboard + DB, suppressed) but skips the email. Independent from the MA/fluctuation
 * {@link AlertRulesToggle} and from the email-subsystem {@code emailEnabled} flag.
 */
@Component
public class SignalAlertsToggle {

    private static final Long CONFIG_ID = 1L;

    private final SignalAlertsToggleRepository repository;
    private volatile boolean enabled = true;

    public SignalAlertsToggle(SignalAlertsToggleRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void loadFromDatabase() {
        SignalAlertsToggleEntity entity = repository.findById(CONFIG_ID).orElse(null);
        if (entity == null) {
            SignalAlertsToggleEntity seed = new SignalAlertsToggleEntity();
            seed.setEnabled(true);
            repository.save(seed);
            enabled = true;
        } else {
            enabled = entity.isEnabled();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        SignalAlertsToggleEntity entity = repository.findById(CONFIG_ID).orElse(new SignalAlertsToggleEntity());
        entity.setEnabled(enabled);
        repository.save(entity);
    }
}
