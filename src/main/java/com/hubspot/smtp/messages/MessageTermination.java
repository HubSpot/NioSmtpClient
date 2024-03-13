package com.hubspot.smtp.messages;

/**
 * Specifies whether message content should be terminated with CRLF.
 *
 */
public enum MessageTermination {
  DO_NOT_TERMINATE,
  ADD_CRLF,
}
