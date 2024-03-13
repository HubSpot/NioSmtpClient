package com.hubspot.smtp.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class DotCrlfBuffer {

  private static final byte[] DOT_CRLF = { '.', '\r', '\n' };

  private static final ByteBuf INSTANCE = Unpooled.unreleasableBuffer(
    Unpooled.directBuffer(3).writeBytes(DOT_CRLF)
  );

  static ByteBuf get() {
    return INSTANCE.duplicate();
  }
}
