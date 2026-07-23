package tech.wenisch.contextcrate.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.SystemSetting;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, Integer> {}
