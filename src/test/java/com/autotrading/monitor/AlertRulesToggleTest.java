package com.autotrading.monitor;

import com.autotrading.entity.AlertRuleToggleEntity;
import com.autotrading.repository.AlertRuleToggleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AlertRulesToggle: DB-backed master switch. Tests load (seed vs persisted)
 * and write-through, with a mocked repository.
 */
class AlertRulesToggleTest {

    private AlertRuleToggleRepository repository;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AlertRuleToggleRepository.class);
    }

    @Test
    @DisplayName("loadFromDatabase seeds enabled=true when the row is missing")
    void seedsDefaultWhenEmpty() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        AlertRulesToggle toggle = new AlertRulesToggle(repository);
        toggle.loadFromDatabase();   // @PostConstruct is not invoked by `new`

        assertTrue(toggle.isEnabled());
        verify(repository).save(argThat((AlertRuleToggleEntity e) -> e.isEnabled()));
    }

    @Test
    @DisplayName("loadFromDatabase restores the persisted value")
    void loadsPersistedValue() {
        AlertRuleToggleEntity entity = new AlertRuleToggleEntity();
        entity.setEnabled(false);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        AlertRulesToggle toggle = new AlertRulesToggle(repository);
        toggle.loadFromDatabase();

        assertFalse(toggle.isEnabled());
    }

    @Test
    @DisplayName("setEnabled updates the cache and persists")
    void setEnabledPersists() {
        AlertRuleToggleEntity entity = new AlertRuleToggleEntity();
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        AlertRulesToggle toggle = new AlertRulesToggle(repository);
        toggle.loadFromDatabase();

        toggle.setEnabled(false);
        assertFalse(toggle.isEnabled());
        verify(repository, atLeastOnce()).save(argThat((AlertRuleToggleEntity e) -> !e.isEnabled()));
    }
}
