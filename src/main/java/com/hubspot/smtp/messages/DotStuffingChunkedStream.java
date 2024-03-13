package com.hubspot.smtp.messages;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.stream.ChunkedStream;
import java.io.InputStream;

/**
 * A {@code ChunkedStream} implementation that applies the SMTP dot-stuffing algorithm
 * to its contents.
 *
 * @see DotStuffing
 */
class DotStuffingChunkedStream extends ChunkedStream {

  private static final byte CR = '\r';
  private static final byte LF = '\n';
  private static final int DEFAULT_CHUNK_SIZE = 64 * 1024;

  private final byte[] trailingBytes = { CR, LF };

  DotStuffingChunkedStream(InputStream in) {
    this(in, DEFAULT_CHUNK_SIZE);
  }

  DotStuffingChunkedStream(InputStream in, int chunkSize) {
    super(in, chunkSize);
  }

  @Override
  public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
    ByteBuf chunk = super.readChunk(allocator);
    if (chunk == null) {
      return null;
    }

    byte[] prevChunkTrailingBytes = new byte[2];
    prevChunkTrailingBytes[0] = trailingBytes[0];
    prevChunkTrailingBytes[1] = trailingBytes[1];

    updateTrailingBytes(chunk);

    boolean appendCRLF =
      isEndOfInput() && !(trailingBytes[0] == CR && trailingBytes[1] == LF);

    return DotStuffing.createDotStuffedBuffer(
      allocator,
      chunk,
      prevChunkTrailingBytes,
      appendCRLF ? MessageTermination.ADD_CRLF : MessageTermination.DO_NOT_TERMINATE
    );
  }

  private void updateTrailingBytes(ByteBuf chunk) {
    if (chunk.readableBytes() == 0) {
      return;
    }

    if (chunk.readableBytes() == 1) {
      trailingBytes[0] = trailingBytes[1];
      trailingBytes[1] = chunk.getByte(0);
      return;
    }

    trailingBytes[0] = chunk.getByte(chunk.readableBytes() - 2);
    trailingBytes[1] = chunk.getByte(chunk.readableBytes() - 1);
  }
}
