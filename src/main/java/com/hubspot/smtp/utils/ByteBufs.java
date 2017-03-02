package com.hubspot.smtp.utils;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class ByteBufs {
  private static final byte CR = '\r';
  private static final byte LF = '\n';
  private static final byte DOT = '.';
  private static final byte[] DOT_DOT = {DOT, DOT};
  private static final byte[] CR_LF = {CR, LF};
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  public static ByteBuf createDotStuffedBuffer(byte[] bytes) {
    int dotIndex = findDotAtBeginningOfLine(bytes, 0);

    if (dotIndex == -1) {
      return Unpooled.wrappedBuffer(bytes, getTerminatingBytes(bytes));
    }

    // Build a CompositeByteBuf to avoid copying
    List<ByteBuf> buffers = new ArrayList<ByteBuf>();
    buffers.add(Unpooled.wrappedBuffer(bytes, 0, dotIndex));
    buffers.add(Unpooled.wrappedBuffer(DOT_DOT));

    int nextDotIndex;
    while ((nextDotIndex = findDotAtBeginningOfLine(bytes, dotIndex + 1)) != -1) {
      buffers.add(Unpooled.wrappedBuffer(bytes, dotIndex + 1, nextDotIndex - dotIndex - 1));
      buffers.add(Unpooled.wrappedBuffer(DOT_DOT));

      dotIndex = nextDotIndex;
    }

    buffers.add(Unpooled.wrappedBuffer(bytes, dotIndex + 1, bytes.length - dotIndex - 1));
    buffers.add(Unpooled.wrappedBuffer(getTerminatingBytes(bytes)));

    return Unpooled.wrappedBuffer(buffers.toArray(new ByteBuf[buffers.size()]));
  }

  // SmtpRequestEncoder will add .CRLF but we need to ensure
  // our messages end with CRLF
  private static byte[] getTerminatingBytes(byte[] bytes) {
    int length = bytes.length;

    if (length >= 2 && bytes[length - 2] == CR && bytes[length - 1] == LF) {
      return EMPTY_BYTE_ARRAY;
    } else {
      return CR_LF;
    }
  }

  private static int findDotAtBeginningOfLine(byte[] bytes, int startAt) {
    for (int i = startAt; i < bytes.length; i++) {
      if (bytes[i] == DOT) {
        return i;
      }

      // advance to the end of the line
      while (++i < bytes.length) {
        if (bytes[i] == LF && bytes[i - 1] == CR) {
          break;
        }
      }
    }

    return -1;
  }
}
