package com.hubspot.smtp.messages;

import com.google.common.io.ByteSource;

public class InputStreamMessageContentTest extends MessageContentTest {
  @Override
  protected MessageContent createContent(byte[] bytes) {
    return new InputStreamMessageContent(ByteSource.wrap(bytes), bytes.length, MessageContentEncoding.UNKNOWN);
  }
}
