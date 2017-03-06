package com.hubspot.smtp.messages;

import java.io.InputStream;

import com.hubspot.smtp.utils.ByteBufs;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.stream.ChunkedStream;

class DotStuffingChunkedStream extends ChunkedStream {
  private static final byte CR = '\r';
  private static final byte LF = '\n';
  private static final int DEFAULT_CHUNK_SIZE = 8192;

  private final int size;
  private final byte[] trailingBytes = { CR, LF };
  private int bytesRead = 0;

  DotStuffingChunkedStream(InputStream in, int size) {
    this(in, size, DEFAULT_CHUNK_SIZE);
  }

  DotStuffingChunkedStream(InputStream in, int size, int chunkSize) {
    super(in, chunkSize);
    this.size = size;
  }

  @Override
  public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
    ByteBuf chunk = super.readChunk(allocator);
    bytesRead += chunk.readableBytes();

    byte[] prevChunkTrailingBytes = new byte[2];
    prevChunkTrailingBytes[0] = trailingBytes[0];
    prevChunkTrailingBytes[1] = trailingBytes[1];

    updateTrailingBytes(chunk);

    boolean isLastChunk = bytesRead >= size;
    boolean appendCRLF = isLastChunk && !(trailingBytes[0] == CR && trailingBytes[1] == LF);

    return ByteBufs.createDotStuffedBuffer(allocator, chunk, prevChunkTrailingBytes, appendCRLF);
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
