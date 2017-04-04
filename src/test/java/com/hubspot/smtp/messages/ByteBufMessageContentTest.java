package com.hubspot.smtp.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

public class ByteBufMessageContentTest extends MessageContentTest {
  @Test
  public void itPerformsDotStuffingIfRequired() {
    ByteBufMessageContent content = createContent(".abc");
    assertThat(extract(content.getDotStuffedContent())).isEqualTo("..abc\r\n");
  }

  @Test
  public void itDoesNotPerformDotStuffingIfNotRequired() {
    ByteBufMessageContent content = createContent(".abc");
    assertThat(extract(content.getContent())).isEqualTo(".abc\r\n");
  }

  @Test
  public void itAddsTerminationIfRequired() {
    ByteBufMessageContent content = createContent("abc");
    assertThat(extract(content.getContent())).isEqualTo("abc\r\n");
  }

  @Test
  public void itDoesNotAddTerminationIfAlreadyPresent() {
    ByteBufMessageContent content = createContent("abc\r\n");
    assertThat(extract(content.getContent())).isEqualTo("abc\r\n");
  }

  private ByteBufMessageContent createContent(String testString) {
    ByteBuf sourceBuffer = Unpooled.wrappedBuffer(testString.getBytes(StandardCharsets.UTF_8));
    return new ByteBufMessageContent(sourceBuffer, MessageContentEncoding.UNKNOWN);
  }

  @Override
  protected ByteBufMessageContent createContent(byte[] bytes) {
    return new ByteBufMessageContent(Unpooled.wrappedBuffer(bytes), MessageContentEncoding.UNKNOWN);
  }

  private String extract(Object o) {
    assertThat(o).isInstanceOf(ByteBuf.class);

    ByteBuf buffer = (ByteBuf) o;

    byte[] bytes = new byte[buffer.readableBytes()];
    buffer.getBytes(0, bytes);
    return new String(bytes, CharsetUtil.UTF_8);
  }
}
