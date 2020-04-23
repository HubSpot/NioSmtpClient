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

/**
 * The parsed response to the EHLO command.
 *
 * <p>This class is thread-safe.
 */
public class EhloResponse {
  static final EhloResponse EMPTY = EhloResponse.parse("", Collections.emptyList());

  private static final Splitter WHITESPACE_SPLITTER = Splitter.on(CharMatcher.whitespace());

  private final String ehloDomain;
  private final ImmutableSet<String> supportedExtensions;

  private EnumSet<Extension> extensions;
  private Optional<Long> maxMessageSize = Optional.empty();
  private boolean isAuthPlainSupported;
  private boolean isAuthLoginSupported;
  private boolean isAuthXoauth2Supported;

  /**
   * Parses an EHLO response.
   *
   * @param  ehloDomain the domain provided with the EHLO command (e.g. "example.com" if "EHLO example.com"
   *                    was sent to the remote server)
   * @param  lines the lines returned by the server
   * @return an {@link EhloResponse} object representing the response
   */
  public static EhloResponse parse(String ehloDomain, Iterable<CharSequence> lines) {
    return parse(ehloDomain, lines, EnumSet.noneOf(Extension.class));
  }

  /**
   * Parses an EHLO response.
   *
   * @param  ehloDomain the domain provided with the EHLO command (e.g. "example.com" if "EHLO example.com"
   *                    was sent to the remote server)
   * @param  lines the lines returned by the server
   * @param  disabledExtensions extensions which should not be marked as supported in the returned {@code EhloResponse},
   *                    even if the server says it supports them
   * @return an {@link EhloResponse} object representing the response
   */
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
      Optional<Long> maybeSize = Optional.ofNullable(Longs.tryParse(parts.get(1)));

      if (maybeSize.isPresent() && maybeSize.get() > 0) {
        maxMessageSize = maybeSize;
      }
    }
  }

  private void parseAuth(List<String> parts) {
    for (String s : parts) {
      if (s.equalsIgnoreCase("plain")) {
        isAuthPlainSupported = true;
      } else if (s.equalsIgnoreCase("login")) {
        isAuthLoginSupported = true;
      } else if (s.equalsIgnoreCase("xoauth2")) {
        isAuthXoauth2Supported = true;
      }
    }
  }

  /**
   * Gets the domain provided with the EHLO command (e.g. "example.com" if "EHLO example.com"
   * was sent to the remote server).
   */
  public String getEhloDomain() {
    return ehloDomain;
  }

  /**
   * Gets whether the specified {@link Extension} is supported by the server.
   */
  public boolean isSupported(Extension ext) {
    return extensions.contains(ext);
  }

  /**
   * Gets whether PLAIN authentication is supported.
   */
  public boolean isAuthPlainSupported() {
    return isAuthPlainSupported;
  }

  /**
   * Gets whether LOGIN authentication is supported.
   */
  public boolean isAuthLoginSupported() {
    return isAuthLoginSupported;
  }

  /**
   * Gets whether XOAUTH2 authentication is supported.
   */
  public boolean isAuthXoauth2Supported() {
    return isAuthXoauth2Supported;
  }

  /**
   * Gets the maximum message size if specified by the server.
   */
  public Optional<Long> getMaxMessageSize() {
    return maxMessageSize;
  }

  /**
   * Gets the raw extensions returned by the server.
   */
  public Set<String> getSupportedExtensions() {
    return supportedExtensions;
  }
}
