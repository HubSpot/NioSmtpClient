package com.hubspot.smtp.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;

public class CrlfTerminatingChunkedStreamTest {
  private static final String CRLF = "\r\n";
  private static final UnpooledByteBufAllocator ALLOCATOR = new UnpooledByteBufAllocator(true);

  @Test
  public void itTerminatesWithCrlf() throws Exception {
    // adds
    assertThat(terminate(".")).isEqualTo("." + CRLF);
    assertThat(terminate("abc")).isEqualTo("abc" + CRLF);
    assertThat(terminate("\r\ndef")).isEqualTo("\r\ndef" + CRLF);
    assertThat(terminate("abc\n")).isEqualTo("abc\n" + CRLF);
    assertThat(terminate("abc\r")).isEqualTo("abc\r" + CRLF);

    // does not add
    assertThat(terminate("\r\n")).isEqualTo("\r\n");
    assertThat(terminate("abc\r\n")).isEqualTo("abc\r\n");
  }

  @Test
  public void itOnlyTerminatesTheFinalChunk() throws Exception {
    assertThat(terminate("0123456789", 3)).isEqualTo("0123456789" + CRLF);
  }

  private String terminate(String testString) throws Exception {
    return terminate(testString, 8192);
  }

  private String terminate(String testString, int chunkSize) throws Exception {
    ByteArrayInputStream stream = new ByteArrayInputStream(testString.getBytes(StandardCharsets.UTF_8));
    CrlfTerminatingChunkedStream chunkedStream = new CrlfTerminatingChunkedStream(stream, chunkSize);

    CompositeByteBuf destBuffer = ALLOCATOR.compositeBuffer();
    while (!chunkedStream.isEndOfInput()) {
      destBuffer.addComponent(true, chunkedStream.readChunk(ALLOCATOR));
    }

    byte[] bytes = new byte[destBuffer.readableBytes()];
    destBuffer.getBytes(0, bytes);
    destBuffer.release();
    return new String(bytes, CharsetUtil.UTF_8);
  }
}
