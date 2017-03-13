package com.hubspot.smtp.client;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.primitives.Longs;

public class EhloResponse {
  static final EhloResponse EMPTY = EhloResponse.parse(Collections.emptyList());

  private static final Splitter WHITESPACE_SPLITTER = Splitter.on(CharMatcher.WHITESPACE);

  private EnumSet<Extension> supportedExtensions;
  private Optional<Long> maxMessageSize = Optional.empty();
  private boolean isAuthPlainSupported;
  private boolean isAuthLoginSupported;

  public static EhloResponse parse(Iterable<CharSequence> lines) {
    return new EhloResponse(lines);
  }

  private EhloResponse(Iterable<CharSequence> lines) {
    supportedExtensions = EnumSet.noneOf(Extension.class);

    for (CharSequence line : lines) {
      List<String> parts = WHITESPACE_SPLITTER.splitToList(line);

      Extension.find(parts.get(0)).ifPresent(supportedExtensions::add);

      switch (parts.get(0).toLowerCase()) {
        case "auth":
          parseAuth(parts);
          break;
        case "size":
          parseSize(parts);
          break;
      }
    }
  }

  private void parseSize(List<String> parts) {
    if (parts.size() > 1) {
      maxMessageSize = Optional.ofNullable(Longs.tryParse(parts.get(1)));
    }
  }

  private void parseAuth(List<String> parts) {
    for (String s : parts) {
      if (s.equalsIgnoreCase("plain")) {
        isAuthPlainSupported = true;
      } else if (s.equalsIgnoreCase("login")) {
        isAuthLoginSupported = true;
      }
    }
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

  public Optional<Long> getMaxMessageSize() {
    return maxMessageSize;
  }
}
