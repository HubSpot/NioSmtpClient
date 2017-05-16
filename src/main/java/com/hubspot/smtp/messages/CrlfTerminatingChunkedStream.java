package com.hubspot.smtp.messages;

import java.io.InputStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.stream.ChunkedStream;

/**
 * A {@code ChunkedStream} implementation that wraps an {@code InputStream}, appending
 * CRLF only if the stream doesn't already end with that byte sequence.
 *
 */
class CrlfTerminatingChunkedStream extends ChunkedStream {
  private static final byte CR = '\r';
  private static final byte LF = '\n';
  private static final int DEFAULT_CHUNK_SIZE = 8192;
  private static final byte[] TRAILING_BYTES = { CR, LF };

  CrlfTerminatingChunkedStream(InputStream in) {
    this(in, DEFAULT_CHUNK_SIZE);
  }

  CrlfTerminatingChunkedStream(InputStream in, int chunkSize) {
    super(in, chunkSize);
  }

  @Override
  public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
    ByteBuf chunk = super.readChunk(allocator);

    if (!isEndOfInput()) {
      return chunk;
    }

    if (isTerminatedWithCrLf(chunk)) {
      return chunk;
    }

    return allocator.compositeBuffer(2).addComponents(true, chunk, allocator.buffer(2).writeBytes(TRAILING_BYTES));
  }

  private boolean isTerminatedWithCrLf(ByteBuf chunk) {
    int length = chunk.readableBytes();

    return length >= 2 &&
        chunk.getByte(length - 2) == CR &&
        chunk.getByte(length - 1) == LF;
  }
}
