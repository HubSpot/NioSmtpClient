package com.hubspot.smtp.messages;

import java.util.OptionalInt;

import com.google.common.io.ByteSource;

public class InputStreamMessageContentTest extends MessageContentTest {
  @Override
  protected MessageContent createContent(byte[] bytes) {
    return new InputStreamMessageContent(ByteSource.wrap(bytes), OptionalInt.of(bytes.length), MessageContentEncoding.UNKNOWN);
  }
}
