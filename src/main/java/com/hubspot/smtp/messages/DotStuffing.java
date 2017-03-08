package com.hubspot.smtp.messages;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ByteProcessor;

final class DotStuffing {
  private static final byte CR = '\r';
  private static final byte LF = '\n';
  private static final byte DOT = '.';
  private static final byte[] DOT_DOT = {DOT, DOT};
  private static final byte[] NOT_CR_LF = {'x', 'x'};
  private static final byte[] CR_LF = {CR, LF};
  private static final ByteBuf DOT_DOT_BUFFER = Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(DOT_DOT));
  private static final ByteBuf CR_LF_BUFFER = Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(CR_LF));

  private DotStuffing() {
    throw new AssertionError("Cannot create static utility class");
  }

  /**
   * Returns a {@link CompositeByteBuf} that contains the same data as {@code sourceBuffer}, but with
   * SMTP dot-stuffing applied, and (if {@code} appendCRLF is true) a CRLF appended.
   *
   * <p>If dot-stuffing is not required, and {@code appendCRLF} is false, {@code sourceBuffer} is
   * returned. In all other cases, {@code allocator} will be used to create a new {@code ByteBuf}
   * with a {@code refCnt} of one.
   *
   * <p>The {@code previousBytes} parameter is used to maintain dot-stuffing across a series
   * of buffers. Pass the last two bytes of a previous buffer here to ensure an initial dot
   * will be escaped if necessary. Passing null indicates this is the first or only buffer
   * for this message.
   *
   * @param allocator the {@code ByteBufAllocator} to use for new {@code ByteBuf}s
   * @param sourceBuffer the source message data
   * @param previousBytes the previous two bytes of the message, or null
   * @param termination whether to append CRLF to the end of the returned buffer
   */
  public static ByteBuf createDotStuffedBuffer(ByteBufAllocator allocator, ByteBuf sourceBuffer, byte[] previousBytes, MessageTermination termination) {
    int dotIndex = findDotAtBeginningOfLine(sourceBuffer, 0, normalisePreviousBytes(previousBytes));

    if (dotIndex == -1) {
      if (termination == MessageTermination.ADD_CRLF) {
        return allocator.compositeBuffer(2).addComponents(true, sourceBuffer.retainedSlice(), CR_LF_BUFFER.slice());
      } else {
        return sourceBuffer;
      }
    }

    // Build a CompositeByteBuf to avoid copying
    CompositeByteBuf compositeByteBuf = allocator.compositeBuffer();
    compositeByteBuf.addComponents(true, sourceBuffer.retainedSlice(0, dotIndex), DOT_DOT_BUFFER.slice());

    int nextDotIndex;
    while ((nextDotIndex = findDotAtBeginningOfLine(sourceBuffer, dotIndex + 1, NOT_CR_LF)) != -1) {
      compositeByteBuf.addComponents(true, sourceBuffer.retainedSlice(dotIndex + 1, nextDotIndex - dotIndex - 1), DOT_DOT_BUFFER.slice());
      dotIndex = nextDotIndex;
    }

    compositeByteBuf.addComponent(true, sourceBuffer.retainedSlice(dotIndex + 1, sourceBuffer.readableBytes() - dotIndex - 1));

    if (termination == MessageTermination.ADD_CRLF) {
      compositeByteBuf.addComponent(true, CR_LF_BUFFER.slice());
    }

    return compositeByteBuf;
  }

  private static byte[] normalisePreviousBytes(byte[] previousBytes) {
    if (previousBytes == null || previousBytes.length == 0) {
      return CR_LF;
    }
    if (previousBytes.length == 1) {
      return new byte[] { 'x', previousBytes[0] };
    }
    if (previousBytes.length > 2) {
      return new byte[] { previousBytes[previousBytes.length - 2], previousBytes[previousBytes.length - 1] };
    }
    return previousBytes;
  }

  private static int findDotAtBeginningOfLine(ByteBuf buffer, int startAt, byte[] previousBytes) {
    int length = buffer.readableBytes();

    if (previousBytes[0] == CR && previousBytes[1] == LF && buffer.getByte(startAt) == DOT) {
      return startAt;
    }

    if (previousBytes[1] == CR && length >= 2 && buffer.getByte(startAt) == LF && buffer.getByte(startAt + 1) == DOT) {
      return startAt + 1;
    }

    int i = startAt;
    while (++i < length) {
      i = buffer.forEachByte(i, length - i, ByteProcessor.FIND_LF);
      if (i == -1) {
        return -1;
      }

      if (buffer.getByte(i - 1) == CR) {
        if (i + 1 < length && buffer.getByte(i + 1) == DOT) {
          return i + 1;
        } else {
          break;
        }
      }
    }

    return -1;
  }
}
