package com.hubspot.smtp.messages;

import java.io.InputStream;
import java.util.function.Supplier;

import com.google.common.io.ByteSource;

import io.netty.buffer.ByteBuf;

public abstract class MessageContent {
  public static MessageContent of(ByteBuf messageBuffer) {
    return of(messageBuffer, MessageContentEncoding.UNKNOWN);
  }

  public static MessageContent of(ByteBuf messageBuffer, MessageContentEncoding encoding) {
    return new ByteBufMessageContent(messageBuffer, encoding);
  }

  public static MessageContent of(Supplier<InputStream> messageStream, int size) {
    return of(messageStream, size, MessageContentEncoding.UNKNOWN);
  }

  public static MessageContent of(Supplier<InputStream> messageStream, int size, MessageContentEncoding encoding) {
    return new InputStreamMessageContent(messageStream, size, encoding);
  }

  public static MessageContent of(ByteSource byteSource, int size) {
    return of(byteSource, size, MessageContentEncoding.UNKNOWN);
  }

  public static MessageContent of(ByteSource byteSource, int size, MessageContentEncoding encoding) {
    return new InputStreamMessageContent(byteSource, size, encoding);
  }

  public abstract int size();

  // only allow subclasses from this package because only certain objects can be returned from getContent
  MessageContent() {}

  public abstract Object getContent();

  public abstract Object getDotStuffedContent();

  public abstract MessageContentEncoding getEncoding();
}
