package com.hubspot.smtp.messages;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

import com.google.common.io.ByteSource;

import io.netty.handler.stream.ChunkedStream;

public class InputStreamMessageContent extends MessageContent {
  private final Supplier<InputStream> stream;
  private final int size;
  private final MessageContentEncoding encoding;

  public InputStreamMessageContent(Supplier<InputStream> stream, int size, MessageContentEncoding encoding) {
    this.stream = stream;
    this.size = size;
    this.encoding = encoding;
  }

  public InputStreamMessageContent(ByteSource byteSource, int size, MessageContentEncoding encoding) {
    this(getStream(byteSource), size, encoding);
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public Object getContent() {
    return new ChunkedStream(stream.get());
  }

  @Override
  public Object getDotStuffedContent() {
    // note: size is hard to predict for dot-stuffed content as
    // the transformation might add a few extra bytes
    return new DotStuffingChunkedStream(stream.get(), size);
  }

  @Override
  public MessageContentEncoding getEncoding() {
    return encoding;
  }

  private static Supplier<InputStream> getStream(ByteSource byteSource) {
    return () -> {
      try {
        return byteSource.openStream();
      } catch (IOException e) {
        throw new RuntimeException("Could not open stream", e);
      }
    };
  }
}
