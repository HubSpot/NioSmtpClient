package com.hubspot.smtp.messages;

import java.io.InputStream;
import java.util.Iterator;
import java.util.OptionalInt;
import java.util.function.Supplier;

import com.google.common.io.ByteSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public abstract class MessageContent {
  public static MessageContent of(ByteBuf messageBuffer) {
    return of(messageBuffer, MessageContentEncoding.UNKNOWN);
  }

  public static MessageContent of(ByteBuf messageBuffer, MessageContentEncoding encoding) {
    return new ByteBufMessageContent(messageBuffer, encoding);
  }

  public static MessageContent of(Supplier<InputStream> messageStream) {
    return of(messageStream, MessageContentEncoding.UNKNOWN);
  }

  public static MessageContent of(Supplier<InputStream> messageStream, MessageContentEncoding encoding) {
    return new InputStreamMessageContent(messageStream, OptionalInt.empty(), encoding);
  }

  public static MessageContent of(Supplier<InputStream> messageStream, MessageContentEncoding encoding, int size) {
    return new InputStreamMessageContent(messageStream, OptionalInt.of(size), encoding);
  }

  public static MessageContent of(ByteSource byteSource) {
    return of(byteSource, MessageContentEncoding.UNKNOWN);
  }

  public static MessageContent of(ByteSource byteSource, MessageContentEncoding encoding) {
    OptionalInt size = byteSource.sizeIfKnown().transform(s -> OptionalInt.of(Math.toIntExact(s))).or(OptionalInt.empty());
    return new InputStreamMessageContent(byteSource, size, encoding);
  }

  public abstract OptionalInt size();

  // only allow subclasses from this package because only certain objects can be returned from getContent
  MessageContent() {}

  public abstract Object getContent();

  public abstract Iterator<ByteBuf> getContentChunkIterator(ByteBufAllocator allocator);

  public abstract Object getDotStuffedContent();

  public abstract MessageContentEncoding getEncoding();

  public abstract float get8bitCharacterProportion();

  public abstract String getContentAsString();
}
