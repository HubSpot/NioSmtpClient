package com.hubspot.smtp.utils;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

public class ByteBufsTest {
  private static final String CRLF = "\r\n";

  @Test
  public void itEnsuresBuffersAreTerminatedWithCRLF() {
    assertEquals(CRLF, dotStuff(""));
    assertEquals("abc" + CRLF, dotStuff("abc"));
    assertEquals("abc\r" + CRLF, dotStuff("abc\r"));
    assertEquals("abc\n" + CRLF, dotStuff("abc\n"));

    assertEquals("abc\r\n", dotStuff("abc\r\n"));
  }

  @Test
  public void itUsesDotStuffing() {
    // adds
    assertEquals(".." + CRLF, dotStuff("."));
    assertEquals("..abc" + CRLF, dotStuff(".abc"));
    assertEquals("\r\n..def" + CRLF, dotStuff("\r\n.def"));
    assertEquals("abc\r\n..def" + CRLF, dotStuff("abc\r\n.def"));
    assertEquals("abc\r\n.." + CRLF, dotStuff("abc\r\n."));
    assertEquals("abc\r\n..def\r\n..ghi\r\n.." + CRLF, dotStuff("abc\r\n.def\r\n.ghi\r\n."));

    // does not add
    assertEquals("abc\r\ndef." + CRLF, dotStuff("abc\r\ndef."));
    assertEquals("abc\r\nd.ef" + CRLF, dotStuff("abc\r\nd.ef"));
    assertEquals("abc\n.def" + CRLF, dotStuff("abc\n.def"));
    assertEquals("abc\r.def" + CRLF, dotStuff("abc\r.def"));
  }

  private String dotStuff(String testString) {
    ByteBuf buffer = ByteBufs.createDotStuffedBuffer(testString.getBytes(StandardCharsets.UTF_8));

    byte[] bytes = new byte[buffer.capacity()];
    buffer.getBytes(0, bytes);
    return new String(bytes, CharsetUtil.UTF_8);
  }
}
