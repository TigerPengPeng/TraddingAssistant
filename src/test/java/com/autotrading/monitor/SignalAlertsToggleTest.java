package com.autotrading.monitor;

import com.autotrading.entity.SignalAlertsToggleEntity;
import com.autotrading.repository.SignalAlertsToggleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SignalAlertsToggle: DB-backed switch for trading-signal alert emails. Mirrors
 * AlertRulesToggleTest — load (seed vs persisted) and write-through.
 */
class SignalAlertsToggleTest {

    private SignalAlertsToggleRepository repository;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(SignalAlertsToggleRepository.class);
    }

    @Test
    @DisplayName("loadFromDatabase seeds enabled=true when the row is missing")
    void seedsDefaultWhenEmpty() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        SignalAlertsToggle toggle = new SignalAlertsToggle(repository);
        toggle.loadFromDatabase();   // @PostConstruct is not invoked by `new`

        assertTrue(toggle.isEnabled());
        verify(repository).save(argThat((SignalAlertsToggleEntity e) -> e.isEnabled()));
    }

    @Test
    @DisplayName("loadFromDatabase restores the persisted value")
    void loadsPersistedValue() {
        SignalAlertsToggleEntity entity = new SignalAlertsToggleEntity();
        entity.setEnabled(false);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        SignalAlertsToggle toggle = new SignalAlertsToggle(repository);
        toggle.loadFromDatabase();

        assertFalse(toggle.isEnabled());
    }

    @Test
    @DisplayName("setEnabled updates the cache and persists")
    void setEnabledPersists() {
        SignalAlertsToggleEntity entity = new SignalAlertsToggleEntity();
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        SignalAlertsToggle toggle = new SignalAlertsToggle(repository);
        toggle.loadFromDatabase();

        toggle.setEnabled(false);
        assertFalse(toggle.isEnabled());
        verify(repository, atLeastOnce()).save(argThat((SignalAlertsToggleEntity e) -> !e.isEnabled()));
    }
}
