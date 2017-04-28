package com.hubspot.smtp.client;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Longs;

public class EhloResponse {
  static final EhloResponse EMPTY = EhloResponse.parse("", Collections.emptyList());

  private static final Splitter WHITESPACE_SPLITTER = Splitter.on(CharMatcher.WHITESPACE);

  private final String ehloDomain;
  private final ImmutableSet<String> supportedExtensions;

  private EnumSet<Extension> extensions;
  private Optional<Long> maxMessageSize = Optional.empty();
  private boolean isAuthPlainSupported;
  private boolean isAuthLoginSupported;

  public static EhloResponse parse(String ehloDomain, Iterable<CharSequence> lines) {
    return parse(ehloDomain, lines, EnumSet.noneOf(Extension.class));
  }

  public static EhloResponse parse(String ehloDomain, Iterable<CharSequence> lines, EnumSet<Extension> disabledExtensions) {
    return new EhloResponse(ehloDomain, lines, disabledExtensions);
  }

  private EhloResponse(String ehloDomain, Iterable<CharSequence> lines, EnumSet<Extension> disabledExtensions) {
    this.ehloDomain = ehloDomain;
    this.extensions = EnumSet.noneOf(Extension.class);

    for (CharSequence line : lines) {
      List<String> parts = WHITESPACE_SPLITTER.splitToList(line);

      Optional<Extension> ext = Extension.find(parts.get(0));
      if (ext.isPresent()) {
        if (disabledExtensions.contains(ext.get())) {
          continue;
        }

        extensions.add(ext.get());
      }

      switch (parts.get(0).toLowerCase()) {
        case "auth":
          parseAuth(parts);
          break;
        case "size":
          parseSize(parts);
          break;
        default:
          break;
      }
    }

    supportedExtensions = ImmutableSet.copyOf(Iterables.transform(lines, CharSequence::toString));
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

  public String getEhloDomain() {
    return ehloDomain;
  }

  public boolean isSupported(Extension ext) {
    return extensions.contains(ext);
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

  public Set<String> getSupportedExtensions() {
    return supportedExtensions;
  }
}
