package com.hubspot.smtp.messages;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;

public class DotStuffingChunkedStreamTest {
  private static final String CRLF = "\r\n";
  private static final UnpooledByteBufAllocator ALLOCATOR = new UnpooledByteBufAllocator(true);
  private static final int SANE_CHUNK_SIZE = 8192;

  @Test
  public void itDotStuffsAndAppendsCRLFWhenNecessary() throws Exception {
    assertDotStuffingWithChunkSize(SANE_CHUNK_SIZE);
  }

  @Test
  public void itDotStuffsAndAppendsCRLFWhenNecessaryWithASmallBlockSize() throws Exception {
    // while these are terrible chunk sizes, they effectively test
    // how we handle new lines at chunk boundaries
    for (int chunkSize = 1; chunkSize < 10; chunkSize++) {
      assertDotStuffingWithChunkSize(chunkSize);
    }
  }

  private void assertDotStuffingWithChunkSize(int chunkSize) throws Exception {
    // adds
    assertEquals(".." + CRLF, dotStuff(".", chunkSize));
    assertEquals("..abc" + CRLF, dotStuff(".abc", chunkSize));
    assertEquals("\r\n..def" + CRLF, dotStuff("\r\n.def", chunkSize));
    assertEquals("abc\r\n..def" + CRLF, dotStuff("abc\r\n.def", chunkSize));
    assertEquals("abc\r\n.." + CRLF, dotStuff("abc\r\n.", chunkSize));
    assertEquals("abc\r\n..def\r\n..ghi\r\n.." + CRLF, dotStuff("abc\r\n.def\r\n.ghi\r\n.", chunkSize));

    // does not add
    assertEquals("abc\r\ndef." + CRLF, dotStuff("abc\r\ndef.", chunkSize));
    assertEquals("abc\r\nd.ef" + CRLF, dotStuff("abc\r\nd.ef", chunkSize));
    assertEquals("abc\n.def" + CRLF, dotStuff("abc\n.def", chunkSize));
    assertEquals("abc\r.def" + CRLF, dotStuff("abc\r.def", chunkSize));
  }

  private String dotStuff(String testString, int chunkSize) throws Exception {
    ByteArrayInputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
    DotStuffingChunkedStream chunkedStream = new DotStuffingChunkedStream(stream, testString.length(), chunkSize);

    CompositeByteBuf destBuffer = ALLOCATOR.compositeBuffer();
    while (!chunkedStream.isEndOfInput()) {
      destBuffer.addComponent(true, chunkedStream.readChunk(ALLOCATOR).retain());
    }

    byte[] bytes = new byte[destBuffer.readableBytes()];
    destBuffer.getBytes(0, bytes);
    return new String(bytes, CharsetUtil.UTF_8);
  }
}
