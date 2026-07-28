package tech.wenisch.contextcrate.repository;

import java.util.Locale;

/** Whitelisted ordering choices for the Documents catalogue. */
public enum DocumentSort {
  CREATED, TITLE, URI, CHUNKS, INDEXED;

  public static DocumentSort from(String value) {
    if (value == null) return CREATED;
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return CREATED;
    }
  }
}
