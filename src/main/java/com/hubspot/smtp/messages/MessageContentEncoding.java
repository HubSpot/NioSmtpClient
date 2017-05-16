package com.hubspot.smtp.messages;

/**
 * Used to specify whether an instance of {@link MessageContent} contains
 * eight-bit characters.
 *
 */
public enum MessageContentEncoding {
  SEVEN_BIT,
  EIGHT_BIT,
  UNKNOWN
}
