package com.hubspot.smtp.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;

public class DotStuffingTest {
  private static final String CRLF = "\r\n";
  private static final UnpooledByteBufAllocator ALLOCATOR = new UnpooledByteBufAllocator(true);

  @Test
  public void itEnsuresBuffersAreTerminatedWithCRLFWithByteBufs() {
    assertThat(dotStuffUsingByteBuf("")).isEqualTo(CRLF);
    assertThat(dotStuffUsingByteBuf("abc")).isEqualTo("abc" + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\r")).isEqualTo("abc\r" + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\n")).isEqualTo("abc\n" + CRLF);
  }

  @Test
  public void itUsesDotStuffingWithByteBufs() {
    // adds
    assertThat(dotStuffUsingByteBuf("")).isEqualTo("" + CRLF);
    assertThat(dotStuffUsingByteBuf(".")).isEqualTo(".." + CRLF);
    assertThat(dotStuffUsingByteBuf(".abc")).isEqualTo("..abc" + CRLF);
    assertThat(dotStuffUsingByteBuf("\r\n.def")).isEqualTo("\r\n..def" + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\r\n.def")).isEqualTo("abc\r\n..def" + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\r\n.")).isEqualTo("abc\r\n.." + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\r\n.def\r\n.ghi\r\n.")).isEqualTo("abc\r\n..def\r\n..ghi\r\n.." + CRLF);

    // does not add
    assertThat(dotStuffUsingByteBuf("abc\r\ndef.")).isEqualTo("abc\r\ndef." + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\r\nd.ef")).isEqualTo("abc\r\nd.ef" + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\n.def")).isEqualTo("abc\n.def" + CRLF);
    assertThat(dotStuffUsingByteBuf("abc\r.def")).isEqualTo("abc\r.def" + CRLF);

    // atStartOfLine
    assertThat(dotStuffUsingByteBuf(".\r\n.", true, false)).isEqualTo("..\r\n..");
    assertThat(dotStuffUsingByteBuf(".\r\n.", false, false)).isEqualTo(".\r\n..");

    // appendCRLF
    assertThat(dotStuffUsingByteBuf("x", true, true)).isEqualTo("x\r\n");
    assertThat(dotStuffUsingByteBuf("x", true, false)).isEqualTo("x");
  }

  @Test
  public void itRetainsSourceByteBufs() {
    String testString = "abc\r\n.def\r\n.ghi\r\n.";

    ByteBuf sourceBuffer = ALLOCATOR.buffer();
    sourceBuffer.writeBytes(testString.getBytes(StandardCharsets.UTF_8));
    assertThat(sourceBuffer.refCnt()).isEqualTo(1);

    ByteBuf destBuffer = DotStuffing.createDotStuffedBuffer(ALLOCATOR, sourceBuffer, null, MessageTermination.ADD_CRLF);
    assertThat(destBuffer.refCnt()).isEqualTo(1);

    // should be able to release both of these successfully
    sourceBuffer.release();
    destBuffer.release();
  }

  private String dotStuffUsingByteBuf(String testString) {
    return dotStuffUsingByteBuf(testString, true, true);
  }

  private String dotStuffUsingByteBuf(String testString, boolean atStartOfLine, boolean appendCRLF) {
    ByteBuf sourceBuffer = ALLOCATOR.buffer();
    sourceBuffer.writeBytes(testString.getBytes(StandardCharsets.UTF_8));

    byte[] previousBytes = atStartOfLine ? null : new byte[] { 'x', 'y' };
    ByteBuf destBuffer = DotStuffing.createDotStuffedBuffer(ALLOCATOR, sourceBuffer, previousBytes,
        appendCRLF ? MessageTermination.ADD_CRLF : MessageTermination.DO_NOT_TERMINATE);

    byte[] bytes = new byte[destBuffer.readableBytes()];
    destBuffer.getBytes(0, bytes);
    return new String(bytes, CharsetUtil.UTF_8);
  }
}
