package com.hubspot.smtp.utils;

import java.util.List;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import io.netty.handler.codec.smtp.SmtpResponse;

/**
 * Helps identify and print {@link SmtpResponse} instances.
 *
 */
public final class SmtpResponses {
  private static final Joiner SPACE_JOINER = Joiner.on(" ").skipNulls();

  private SmtpResponses() {
    throw new AssertionError("Cannot create static utility class");
  }

  public static String toString(SmtpResponse response) {
    if (response.details().size() == 0) {
      return Integer.toString(response.code());
    } else {
      return response.code() + " " + SPACE_JOINER.join(response.details());
    }
  }

  public static List<String> getLines(SmtpResponse response) {
    if (response.details().size() == 0) {
      return ImmutableList.of(Integer.toString(response.code()));
    }

    String[] lines = new String[response.details().size()];

    for (int i = 0; i < response.details().size(); i++) {
      StringBuilder responseBuilder = new StringBuilder();

      responseBuilder.append(response.code());

      if (i == response.details().size() - 1) {
        responseBuilder.append(" ");
      } else {
        responseBuilder.append("-");
      }

      responseBuilder.append(response.details().get(i));

      lines[i] = responseBuilder.toString();
    }

    return ImmutableList.copyOf(lines);
  }

  public static boolean isTransientError(SmtpResponse response) {
    return response.code() >= 400 && response.code() < 500;
  }

  public static boolean isPermanentError(SmtpResponse response) {
    return response.code() >= 500;
  }

  public static boolean isError(SmtpResponse response) {
    return isTransientError(response) || isPermanentError(response);
  }
}
