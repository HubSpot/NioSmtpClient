package com.hubspot.smtp.messages;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

import com.google.common.io.ByteSource;

import io.netty.handler.stream.ChunkedStream;

public class InputStreamMessageContent extends MessageContent {
  private final ChunkedStream chunkedStream;
  private final int size;

  public InputStreamMessageContent(Supplier<InputStream> stream, int size, boolean applyDotStuffing) {
    this(stream.get(), size, applyDotStuffing);
  }

  public InputStreamMessageContent(ByteSource byteSource, int size, boolean applyDotStuffing) {
    this(getStream(byteSource), size, applyDotStuffing);
  }

  private InputStreamMessageContent(InputStream stream, int size, boolean applyDotStuffing) {
    // note size is hard to predict if applyDotStuffing is true - the transformation might add
    // a few extra bytes
    this.size = size;
    this.chunkedStream = applyDotStuffing ? new DotStuffingChunkedStream(stream, size) : new ChunkedStream(stream);
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

  @Override
  public int size() {
    return size;
  }

  @Override
  public Object get8BitMimeEncodedContent() {
    return chunkedStream;
  }
}
