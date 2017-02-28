package com.hubspot.smtp.client;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public enum SupportedExtensions {
  PIPELINING("pipelining"),
  EIGHT_BIT_MIME("8bitmime"),
  AUTH("auth"),
  XCLIENT("xclient"),
  XFORWARD("xforward"),
  ENHANCEDSTATUSCODES("enhancedstatuscodes"),
  DSN("dsn");

  private final String lowerCaseName;

  private static Map<String, SupportedExtensions> NAME_TO_EXTENSION = Arrays.stream(SupportedExtensions.values())
      .collect(Collectors.toMap(SupportedExtensions::getLowerCaseName, v -> v));

  public static Optional<SupportedExtensions> find(String name) {
    return Optional.ofNullable(NAME_TO_EXTENSION.get(name.toLowerCase()));
  }

  SupportedExtensions(String lowerCaseName) {
    this.lowerCaseName = lowerCaseName;
  }

  public String getLowerCaseName() {
    return lowerCaseName;
  }
}
