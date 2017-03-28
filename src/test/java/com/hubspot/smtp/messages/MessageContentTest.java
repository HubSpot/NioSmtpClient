package com.hubspot.smtp.messages;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public abstract class MessageContentTest {
  @Test
  public void itCounts8BitCharacters() {
    byte[] allZeros = new byte[] {
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00
    };

    assertThat(createContent(allZeros).count8bitCharacters()).isEqualTo(0);

    byte[] allHighBit = new byte[] {
        (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
        (byte) 0x80, (byte) 0x80
    };

    assertThat(createContent(allHighBit).count8bitCharacters()).isEqualTo(allHighBit.length);

    byte[] mixed = new byte[] {
        (byte) 0x80, (byte) 0x00, (byte) 0x80, (byte) 0x00, (byte) 0x80, (byte) 0x00, (byte) 0x80, (byte) 0x00,
        (byte) 0x80, (byte) 0x00
    };

    assertThat(createContent(mixed).count8bitCharacters()).isEqualTo(mixed.length / 2);
  }

  protected abstract MessageContent createContent(byte[] bytes);
}
