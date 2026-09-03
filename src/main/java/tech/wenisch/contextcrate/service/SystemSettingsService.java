package tech.wenisch.contextcrate.service;

import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.SystemSetting;
import tech.wenisch.contextcrate.repository.SystemSettingRepository;

@Service
public class SystemSettingsService {
  private final SystemSettingRepository settings;
  public SystemSettingsService(SystemSettingRepository settings) { this.settings = settings; }
  @Transactional(readOnly = true) public ZoneId timeZone() { return ZoneId.of(current().getTimeZone()); }
  @Transactional(readOnly = true) public SystemSetting current() { return settings.findById(1).orElseThrow(); }
}
