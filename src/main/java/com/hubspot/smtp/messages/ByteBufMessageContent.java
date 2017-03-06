package com.hubspot.smtp.messages;

import com.hubspot.smtp.utils.ByteBufs;

import io.netty.buffer.ByteBuf;

public class ByteBufMessageContent extends MessageContent {
  private final ByteBuf buffer;
  private final int size;

  public ByteBufMessageContent(ByteBuf buffer, boolean applyDotStuffing) {
    this.buffer = applyDotStuffing ? ByteBufs.createDotStuffedBuffer(buffer.alloc(), buffer, null, true) : buffer;
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
