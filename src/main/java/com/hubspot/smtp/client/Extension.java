package com.hubspot.smtp.client;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An Extended SMTP feature.
 *
 */
public enum Extension {
  AUTH("auth"),
  CHUNKING("chunking"),
  DSN("dsn"),
  EIGHT_BIT_MIME("8bitmime"),
  ENHANCEDSTATUSCODES("enhancedstatuscodes"),
  PIPELINING("pipelining"),
  SIZE("size"),
  STARTTLS("starttls"),
  XCLIENT("xclient"),
  XFORWARD("xforward"),
  SMTPUTF8("smtputf8");

  private final String lowerCaseName;

  private static Map<String, Extension> NAME_TO_EXTENSION = Arrays
    .stream(Extension.values())
    .collect(Collectors.toMap(Extension::getLowerCaseName, v -> v));

  public static Optional<Extension> find(String name) {
    return Optional.ofNullable(NAME_TO_EXTENSION.get(name.toLowerCase()));
  }

  Extension(String lowerCaseName) {
    this.lowerCaseName = lowerCaseName;
  }

  public String getLowerCaseName() {
    return lowerCaseName;
  }
}
