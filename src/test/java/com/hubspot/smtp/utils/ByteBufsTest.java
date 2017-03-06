package com.hubspot.smtp.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;

public class ByteBufsTest {
  private static final String CRLF = "\r\n";
  private static final UnpooledByteBufAllocator ALLOCATOR = new UnpooledByteBufAllocator(true);

  @Test
  public void itEnsuresBuffersAreTerminatedWithCRLFWithByteArrays() {
    assertEquals(CRLF, dotStuffUsingByteArray(""));
    assertEquals("abc" + CRLF, dotStuffUsingByteArray("abc"));
    assertEquals("abc\r" + CRLF, dotStuffUsingByteArray("abc\r"));
    assertEquals("abc\n" + CRLF, dotStuffUsingByteArray("abc\n"));

    assertEquals("abc\r\n", dotStuffUsingByteArray("abc\r\n"));
  }

  @Test
  public void itUsesDotStuffingWithByteArrays() {
    // adds
    assertEquals(".." + CRLF, dotStuffUsingByteArray("."));
    assertEquals("..abc" + CRLF, dotStuffUsingByteArray(".abc"));
    assertEquals("\r\n..def" + CRLF, dotStuffUsingByteArray("\r\n.def"));
    assertEquals("abc\r\n..def" + CRLF, dotStuffUsingByteArray("abc\r\n.def"));
    assertEquals("abc\r\n.." + CRLF, dotStuffUsingByteArray("abc\r\n."));
    assertEquals("abc\r\n..def\r\n..ghi\r\n.." + CRLF, dotStuffUsingByteArray("abc\r\n.def\r\n.ghi\r\n."));

    // does not add
    assertEquals("abc\r\ndef." + CRLF, dotStuffUsingByteArray("abc\r\ndef."));
    assertEquals("abc\r\nd.ef" + CRLF, dotStuffUsingByteArray("abc\r\nd.ef"));
    assertEquals("abc\n.def" + CRLF, dotStuffUsingByteArray("abc\n.def"));
    assertEquals("abc\r.def" + CRLF, dotStuffUsingByteArray("abc\r.def"));
  }

  @Test
  public void itEnsuresBuffersAreTerminatedWithCRLFWithByteBufs() {
    assertEquals(CRLF, dotStuffUsingByteBuf(""));
    assertEquals("abc" + CRLF, dotStuffUsingByteBuf("abc"));
    assertEquals("abc\r" + CRLF, dotStuffUsingByteBuf("abc\r"));
    assertEquals("abc\n" + CRLF, dotStuffUsingByteBuf("abc\n"));
  }

  @Test
  public void itUsesDotStuffingWithByteBufs() {
    // adds
    assertEquals("" + CRLF, dotStuffUsingByteBuf(""));
    assertEquals(".." + CRLF, dotStuffUsingByteBuf("."));
    assertEquals("..abc" + CRLF, dotStuffUsingByteBuf(".abc"));
    assertEquals("\r\n..def" + CRLF, dotStuffUsingByteBuf("\r\n.def"));
    assertEquals("abc\r\n..def" + CRLF, dotStuffUsingByteBuf("abc\r\n.def"));
    assertEquals("abc\r\n.." + CRLF, dotStuffUsingByteBuf("abc\r\n."));
    assertEquals("abc\r\n..def\r\n..ghi\r\n.." + CRLF, dotStuffUsingByteBuf("abc\r\n.def\r\n.ghi\r\n."));

    // does not add
    assertEquals("abc\r\ndef." + CRLF, dotStuffUsingByteBuf("abc\r\ndef."));
    assertEquals("abc\r\nd.ef" + CRLF, dotStuffUsingByteBuf("abc\r\nd.ef"));
    assertEquals("abc\n.def" + CRLF, dotStuffUsingByteBuf("abc\n.def"));
    assertEquals("abc\r.def" + CRLF, dotStuffUsingByteBuf("abc\r.def"));

    // atStartOfLine
    assertEquals("..\r\n..", dotStuffUsingByteBuf(".\r\n.", true, false));
    assertEquals(".\r\n..", dotStuffUsingByteBuf(".\r\n.", false, false));

    // appendCRLF
    assertEquals("x\r\n", dotStuffUsingByteBuf("x", true, true));
    assertEquals("x", dotStuffUsingByteBuf("x", true, false));
  }

  @Test
  public void itRetainsSourceByteBufs() {
    String testString = "abc\r\n.def\r\n.ghi\r\n.";

    ByteBuf sourceBuffer = ALLOCATOR.buffer();
    sourceBuffer.writeBytes(testString.getBytes(StandardCharsets.UTF_8));
    assertThat(sourceBuffer.refCnt()).isEqualTo(1);

    ByteBuf destBuffer = ByteBufs.createDotStuffedBuffer(ALLOCATOR, sourceBuffer, null, true);
    assertThat(destBuffer.refCnt()).isEqualTo(1);

    // should be able to release both of these successfully
    sourceBuffer.release();
    destBuffer.release();
  }

  @Test
  public void itRetainsTheSourceByteIfNoProcessingIsRequired() {
    String testString = "abc";

    ByteBuf sourceBuffer = ALLOCATOR.buffer();
    sourceBuffer.writeBytes(testString.getBytes(StandardCharsets.UTF_8));
    assertThat(sourceBuffer.refCnt()).isEqualTo(1);

    ByteBuf destBuffer = ByteBufs.createDotStuffedBuffer(ALLOCATOR, sourceBuffer, null, false);
    assertThat(destBuffer).isSameAs(sourceBuffer);
    assertThat(sourceBuffer.refCnt()).isEqualTo(2);

    // should be able to release both of these successfully
    sourceBuffer.release();
    destBuffer.release();
  }

  private String dotStuffUsingByteArray(String testString) {
    ByteBuf buffer = ByteBufs.createDotStuffedBuffer(testString.getBytes(StandardCharsets.UTF_8));

    byte[] bytes = new byte[buffer.readableBytes()];
    buffer.getBytes(0, bytes);
    return new String(bytes, CharsetUtil.UTF_8);
  }

  private String dotStuffUsingByteBuf(String testString) {
    return dotStuffUsingByteBuf(testString, true, true);
  }

  private String dotStuffUsingByteBuf(String testString, boolean atStartOfLine, boolean appendCRLF) {
    ByteBuf sourceBuffer = ALLOCATOR.buffer();
    sourceBuffer.writeBytes(testString.getBytes(StandardCharsets.UTF_8));

    byte[] previousBytes = atStartOfLine ? null : new byte[] { 'x', 'y' };
    ByteBuf destBuffer = ByteBufs.createDotStuffedBuffer(ALLOCATOR, sourceBuffer, previousBytes, appendCRLF);

    byte[] bytes = new byte[destBuffer.readableBytes()];
    destBuffer.getBytes(0, bytes);
    return new String(bytes, CharsetUtil.UTF_8);
  }
}
