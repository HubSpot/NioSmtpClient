package com.hubspot.smtp.messages;

import java.io.InputStream;

import io.netty.handler.stream.ChunkedStream;

public class InputStreamMessageContent extends MessageContent {
  private final ChunkedStream chunkedStream;
  private final int size;

  public InputStreamMessageContent(InputStream stream, int size, boolean applyDotStuffing) {
    // note size is hard to predict if applyDotStuffing is true - the transformation might add
    // a few extra bytes
    this.size = size;
    this.chunkedStream = applyDotStuffing ? new DotStuffingChunkedStream(stream, size) : new ChunkedStream(stream);
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
