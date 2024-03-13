package com.hubspot.smtp.client;

import com.hubspot.smtp.utils.SmtpResponses;
import io.netty.handler.codec.smtp.SmtpResponse;
import java.util.List;
import java.util.stream.Collectors;

public class ResponseException extends RuntimeException {

  public ResponseException(
    Throwable cause,
    String debugString,
    List<SmtpResponse> responses
  ) {
    super(createMessage(debugString, responses), cause);
  }

  private static String createMessage(String debugString, List<SmtpResponse> responses) {
    String responsesSoFar = responses.isEmpty()
      ? "<none>"
      : String.join(
        ",",
        responses.stream().map(SmtpResponses::toString).collect(Collectors.toList())
      );

    return String.format(
      "Received an exception while waiting for a response to [%s]; responses so far: %s",
      debugString,
      responsesSoFar
    );
  }
}
