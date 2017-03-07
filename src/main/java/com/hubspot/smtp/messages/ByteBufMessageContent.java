package com.hubspot.smtp.messages;

import io.netty.buffer.ByteBuf;

public class ByteBufMessageContent extends MessageContent {
  private final ByteBuf buffer;
  private final int size;

  public ByteBufMessageContent(ByteBuf buffer, MessageContentEncoding encoding) {
    this.buffer = encoding == MessageContentEncoding.REQUIRES_DOT_STUFFING ? wrap(buffer) : buffer;
    this.size = this.buffer.readableBytes();
  }

  private ByteBuf wrap(ByteBuf buffer) {
    int length = buffer.readableBytes();
    boolean isTerminated = length >= 2 && buffer.getByte(length - 2) == '\r' && buffer.getByte(length - 1) == '\n';

    return DotStuffing.createDotStuffedBuffer(buffer.alloc(), buffer, null,
        isTerminated ? MessageTermination.DO_NOT_TERMINATE : MessageTermination.ADD_CRLF);
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public Object get8BitMimeEncodedContent() {
    return buffer;
  }
}
