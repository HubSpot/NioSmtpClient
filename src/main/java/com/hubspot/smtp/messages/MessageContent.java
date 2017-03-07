package com.hubspot.smtp.messages;

import java.io.InputStream;
import java.util.function.Supplier;

import com.google.common.io.ByteSource;

import io.netty.buffer.ByteBuf;

public abstract class MessageContent {
  public static MessageContent of(ByteBuf messageBuffer) {
    return new ByteBufMessageContent(messageBuffer, MessageContentEncoding.REQUIRES_DOT_STUFFING);
  }

  public static MessageContent of(Supplier<InputStream> messageStream, int size, MessageContentEncoding encoding) {
    return new InputStreamMessageContent(messageStream, size, encoding);
  }

  public static MessageContent of(ByteSource byteSource, int size, MessageContentEncoding encoding) {
    return new InputStreamMessageContent(byteSource, size, encoding);
  }

  public abstract int size();

  // only allow subclasses from this package because only certain objects can be returned from
  // get8BitMimeEncodedContent / get7BitEncodedContent
  MessageContent() {}

  public abstract Object get8BitMimeEncodedContent();

  public Object get7BitEncodedContent() {
    return get8BitMimeEncodedContent();
  }
}
