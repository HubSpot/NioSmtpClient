package com.hubspot.smtp.client;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;

public class EhloResponse {
  private static final Splitter WHITESPACE_SPLITTER = Splitter.on(CharMatcher.WHITESPACE);

  static final EhloResponse NULL_RESPONSE = EhloResponse.parse(Collections.emptyList());

  private final EnumSet<Extension> supportedExtensions;
  private final boolean isAuthPlainSupported;
  private final boolean isAuthLoginSupported;

  public static EhloResponse parse(Iterable<CharSequence> lines) {
    boolean isAuthLoginSupported = false;
    boolean isAuthPlainSupported = false;

    EnumSet<Extension> discoveredExtensions = EnumSet.noneOf(Extension.class);

    for (CharSequence ext : lines) {
      List<String> parts = WHITESPACE_SPLITTER.splitToList(ext);
      Extension.find(parts.get(0)).ifPresent(discoveredExtensions::add);

      if (parts.get(0).equalsIgnoreCase("auth") && parts.size() > 1) {
        for (int i = 1; i < parts.size(); i++) {
          if (parts.get(i).equalsIgnoreCase("plain")) {
            isAuthPlainSupported = true;
          } else if (parts.get(i).equalsIgnoreCase("login")) {
            isAuthLoginSupported = true;
          }
        }
      }
    }

    return new EhloResponse(discoveredExtensions, isAuthPlainSupported, isAuthLoginSupported);
  }

  private EhloResponse(EnumSet<Extension> supportedExtensions, boolean isAuthPlainSupported, boolean isAuthLoginSupported) {
    this.supportedExtensions = supportedExtensions;
    this.isAuthPlainSupported = isAuthPlainSupported;
    this.isAuthLoginSupported = isAuthLoginSupported;
  }

  public boolean isSupported(Extension ext) {
    return supportedExtensions.contains(ext);
  }

  public boolean isAuthPlainSupported() {
    return isAuthPlainSupported;
  }

  public boolean isAuthLoginSupported() {
    return isAuthLoginSupported;
  }
}
