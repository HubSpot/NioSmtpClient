package com.hubspot.smtp.messages;

import com.hubspot.smtp.utils.ByteBufs;

import io.netty.buffer.ByteBuf;

public class ByteBufMessageContent extends MessageContent {
  private final ByteBuf buffer;
  private final int size;

  public ByteBufMessageContent(ByteBuf buffer, MessageContentEncoding encoding) {
    this.buffer = encoding == MessageContentEncoding.REQUIRES_DOT_STUFFING ?
        ByteBufs.createDotStuffedBuffer(buffer.alloc(), buffer, null, MessageTermination.ADD_CRLF_IF_NECESSARY) : buffer;
    size = this.buffer.readableBytes();
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
