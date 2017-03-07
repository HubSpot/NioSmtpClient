package com.hubspot.smtp.messages;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ByteBufMessageContent extends MessageContent {
  private static final byte CR = '\r';
  private static final byte LF = '\n';
  private static final byte[] CR_LF = {CR, LF};
  private static final ByteBuf CR_LF_BUFFER = Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(CR_LF));

  private final ByteBuf buffer;
  private final int size;

  public ByteBufMessageContent(ByteBuf buffer, MessageContentEncoding encoding) {
    if (encoding == MessageContentEncoding.REQUIRES_DOT_STUFFING) {
      this.buffer = dotStuff(buffer);
    } else {
      this.buffer = isTerminated(buffer) ? buffer : terminate(buffer);
    }

    this.size = this.buffer.readableBytes();
  }

  private static ByteBuf terminate(ByteBuf buffer) {
    return buffer.alloc()
        .compositeBuffer(2)
        .addComponents(true, buffer, CR_LF_BUFFER.slice());
  }

  private static ByteBuf dotStuff(ByteBuf buffer) {
    return DotStuffing.createDotStuffedBuffer(buffer.alloc(), buffer, null,
        isTerminated(buffer) ? MessageTermination.DO_NOT_TERMINATE : MessageTermination.ADD_CRLF);
  }

  private static boolean isTerminated(ByteBuf buffer) {
    int length = buffer.readableBytes();
    return length >= 2 && buffer.getByte(length - 2) == '\r' && buffer.getByte(length - 1) == '\n';
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
