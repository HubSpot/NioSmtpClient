package com.hubspot.smtp.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

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
    assertThat(dotStuff(".", chunkSize)).isEqualTo(".." + CRLF);
    assertThat(dotStuff(".abc", chunkSize)).isEqualTo("..abc" + CRLF);
    assertThat(dotStuff("\r\n.def", chunkSize)).isEqualTo("\r\n..def" + CRLF);
    assertThat(dotStuff("abc\r\n.def", chunkSize)).isEqualTo("abc\r\n..def" + CRLF);
    assertThat(dotStuff("abc\r\n.", chunkSize)).isEqualTo("abc\r\n.." + CRLF);
    assertThat(dotStuff("abc\r\n.def\r\n.ghi\r\n.", chunkSize)).isEqualTo("abc\r\n..def\r\n..ghi\r\n.." + CRLF);

    // does not add
    assertThat(dotStuff("abc\r\ndef.", chunkSize)).isEqualTo("abc\r\ndef." + CRLF);
    assertThat(dotStuff("abc\r\nd.ef", chunkSize)).isEqualTo("abc\r\nd.ef" + CRLF);
    assertThat(dotStuff("abc\n.def", chunkSize)).isEqualTo("abc\n.def" + CRLF);
    assertThat(dotStuff("abc\r.def", chunkSize)).isEqualTo("abc\r.def" + CRLF);
  }

  private String dotStuff(String testString, int chunkSize) throws Exception {
    ByteArrayInputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
    DotStuffingChunkedStream chunkedStream = new DotStuffingChunkedStream(stream, chunkSize);

    CompositeByteBuf destBuffer = ALLOCATOR.compositeBuffer();

    while (!chunkedStream.isEndOfInput()) {
      destBuffer.addComponent(true, chunkedStream.readChunk(ALLOCATOR));
    }

    byte[] bytes = new byte[destBuffer.readableBytes()];
    destBuffer.getBytes(0, bytes);

    ReferenceCountUtil.release(destBuffer);
    return new String(bytes, CharsetUtil.UTF_8);
  }
}
