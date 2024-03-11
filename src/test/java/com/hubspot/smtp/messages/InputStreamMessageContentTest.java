package com.hubspot.smtp.messages;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.io.ByteSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import org.junit.Test;

public class InputStreamMessageContentTest extends MessageContentTest {

  @Override
  protected MessageContent createContent(byte[] bytes) {
    return new InputStreamMessageContent(
      ByteSource.wrap(bytes),
      OptionalInt.of(bytes.length),
      MessageContentEncoding.UNKNOWN
    );
  }

  @Test
  public void itUsesAFixedValueForStreamsThatDoNotSupportMark() {
    ByteArrayInputStream stream = new ByteArrayInputStream(
      "hello".getBytes(StandardCharsets.UTF_8)
    ) {
      @Override
      public boolean markSupported() {
        return false;
      }
    };

    MessageContent content = new InputStreamMessageContent(
      () -> stream,
      OptionalInt.empty(),
      MessageContentEncoding.UNKNOWN
    );
    assertThat(content.get8bitCharacterProportion()).isEqualTo(0.1F);
  }
}
