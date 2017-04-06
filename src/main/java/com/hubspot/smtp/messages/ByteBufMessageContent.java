package com.hubspot.smtp.messages;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.OptionalInt;

import com.google.common.collect.Iterators;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

public class ByteBufMessageContent extends MessageContent {
  private static final long LONG_WITH_HIGH_BITS_SET = 0x8080808080808080L;
  private static final float UNCOUNTED = -1F;
  private static final byte CR = '\r';
  private static final byte LF = '\n';
  private static final byte[] CR_LF = {CR, LF};
  private static final ByteBuf CR_LF_BUFFER = Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(CR_LF));

  private final ByteBuf buffer;
  private final int size;
  private final MessageContentEncoding encoding;

  private float eightBitCharProportion = UNCOUNTED;

  public ByteBufMessageContent(ByteBuf buffer, MessageContentEncoding encoding) {
    this.buffer = buffer;
    this.size = buffer.readableBytes();
    this.encoding = encoding;
  }

  @Override
  public Object getContent() {
    return isTerminated(buffer) ? buffer : terminate(buffer);
  }

  @Override
  public Iterator<ByteBuf> getContentChunkIterator(ByteBufAllocator allocator) {
    return Iterators.singletonIterator((ByteBuf) getContent());
  }

  @Override
  public Object getDotStuffedContent() {
    return dotStuff(buffer);
  }

  @Override
  public MessageContentEncoding getEncoding() {
    return encoding;
  }

  @Override
  public OptionalInt size() {
    return OptionalInt.of(size);
  }

  @Override
  public float get8bitCharacterProportion() {
    if (eightBitCharProportion != UNCOUNTED) {
      return eightBitCharProportion;
    }

    int eightBitCharCount = 0;
    buffer.markReaderIndex();

    // read content as longs for performance
    while (buffer.readableBytes() >= 8) {
      long bytes = buffer.readLong();

      if (0 != (bytes & LONG_WITH_HIGH_BITS_SET)) {
        for (int i = 0; i < 8; i++) {
          if (0 != (bytes & (0x80 << i * 8))) {
            eightBitCharCount++;
          }
        }
      }
    }

    // read any remaining bytes
    while (buffer.readableBytes() > 0) {
      if (0 != (buffer.readByte() & 0x80)) {
        eightBitCharCount++;
      }
    }

    buffer.resetReaderIndex();

    eightBitCharProportion = 1.0F * eightBitCharCount / size;
    return eightBitCharProportion;
  }

  @Override
  public String getContentAsString() {
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static ByteBuf terminate(ByteBuf buffer) {
    return buffer.alloc()
        .compositeBuffer(2)
        .addComponents(true, buffer, CR_LF_BUFFER.slice());
  }

  private static boolean isTerminated(ByteBuf buffer) {
    int length = buffer.readableBytes();
    return length >= 2 && buffer.getByte(length - 2) == '\r' && buffer.getByte(length - 1) == '\n';
  }

  private static ByteBuf dotStuff(ByteBuf buffer) {
    return DotStuffing.createDotStuffedBuffer(buffer.alloc(), buffer, null,
        isTerminated(buffer) ? MessageTermination.DO_NOT_TERMINATE : MessageTermination.ADD_CRLF);
  }
}
