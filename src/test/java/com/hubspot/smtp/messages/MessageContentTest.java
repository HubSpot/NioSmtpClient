package com.hubspot.smtp.messages;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public abstract class MessageContentTest {

  @Test
  public void itCounts8BitCharacters() {
    byte[] allZeros = new byte[] {
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
    };

    assertThat(createContent(allZeros).get8bitCharacterProportion()).isEqualTo(0.0F);

    byte[] allHighBit = new byte[] {
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
      (byte) 0x80,
    };

    assertThat(createContent(allHighBit).get8bitCharacterProportion()).isEqualTo(1.0F);

    byte[] mixed = new byte[] {
      (byte) 0x80,
      (byte) 0x00,
      (byte) 0x80,
      (byte) 0x00,
      (byte) 0x80,
      (byte) 0x00,
      (byte) 0x80,
      (byte) 0x00,
      (byte) 0x80,
      (byte) 0x00,
    };

    assertThat(createContent(mixed).get8bitCharacterProportion()).isEqualTo(0.5F);
  }

  protected abstract MessageContent createContent(byte[] bytes);
}
