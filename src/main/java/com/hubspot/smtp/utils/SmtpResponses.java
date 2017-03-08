package com.hubspot.smtp.utils;

import com.google.common.base.Joiner;

import io.netty.handler.codec.smtp.SmtpResponse;

public final class SmtpResponses {
  private static final Joiner SPACE_JOINER = Joiner.on(" ");

  private SmtpResponses() {
    throw new AssertionError("Cannot create static utility class");
  }

  public static String toString(SmtpResponse response) {
    return response.code() + " " + SPACE_JOINER.join(response.details());
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
