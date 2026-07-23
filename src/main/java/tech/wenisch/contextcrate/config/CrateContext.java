package tech.wenisch.contextcrate.config;

import java.util.UUID;
import tech.wenisch.contextcrate.domain.CrateIds;

public final class CrateContext {
  private static final ThreadLocal<UUID> CURRENT=ThreadLocal.withInitial(()->CrateIds.LEGACY);
  private CrateContext(){}
  public static UUID current(){return CURRENT.get();}
  public static Scope use(UUID crateId){UUID previous=CURRENT.get();CURRENT.set(crateId);return ()->CURRENT.set(previous);}
  public interface Scope extends AutoCloseable{@Override void close();}
}
