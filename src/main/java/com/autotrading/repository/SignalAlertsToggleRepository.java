package com.autotrading.repository;

import com.autotrading.entity.SignalAlertsToggleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the singleton signal-alerts toggle row.
 */
@Repository
public interface SignalAlertsToggleRepository extends JpaRepository<SignalAlertsToggleEntity, Long> {
}
