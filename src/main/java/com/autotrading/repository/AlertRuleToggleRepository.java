package com.autotrading.repository;

import com.autotrading.entity.AlertRuleToggleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the singleton alert-rule toggle row.
 */
@Repository
public interface AlertRuleToggleRepository extends JpaRepository<AlertRuleToggleEntity, Long> {
}
