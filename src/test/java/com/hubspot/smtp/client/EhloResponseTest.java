package com.hubspot.smtp.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class EhloResponseTest {
  @Test
  public void itHasAnEmptyResponseThatSupportsNothing() {
    assertThat(EhloResponse.EMPTY.isSupported(Extension.PIPELINING)).isFalse();
    assertThat(EhloResponse.EMPTY.isAuthPlainSupported()).isFalse();
    assertThat(EhloResponse.EMPTY.isAuthLoginSupported()).isFalse();
  }
  
  @Test
  public void itParsesATypicalResponse() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com",
        "AUTH PLAIN LOGIN",
        "8BITMIME",
        "STARTTLS",
        "SIZE");

    assertThat(response.isSupported(Extension.EIGHT_BIT_MIME)).isTrue();
    assertThat(response.isSupported(Extension.STARTTLS)).isTrue();
    assertThat(response.isSupported(Extension.SIZE)).isTrue();

    assertThat(response.isSupported(Extension.PIPELINING)).isFalse();

    assertThat(response.isAuthPlainSupported()).isTrue();
    assertThat(response.isAuthLoginSupported()).isTrue();
  }

  @Test
  public void itIgnoresDisabledExtensions() {
    EhloResponse response = EhloResponse.parse("", Lists.newArrayList("smtp.example.com Hello client.example.com", "8BITMIME", "STARTTLS"),
        EnumSet.of(Extension.EIGHT_BIT_MIME));

    assertThat(response.isSupported(Extension.EIGHT_BIT_MIME)).isFalse();
    assertThat(response.isSupported(Extension.STARTTLS)).isTrue();
  }

  @Test
  public void itExposesSupportedExtensionsAsStrings() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com",
        "AUTH PLAIN LOGIN",
        "8BITMIME",
        "STARTTLS",
        "SIZE");

    assertThat(response.getSupportedExtensions()).isEqualTo(Sets.newHashSet("smtp.example.com Hello client.example.com",
        "AUTH PLAIN LOGIN",
        "8BITMIME",
        "STARTTLS",
        "SIZE"));
  }

  @Test
  public void itParsesEightBitMime() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "8BITMIME");
    assertThat(response.isSupported(Extension.EIGHT_BIT_MIME)).isTrue();
  }

  @Test
  public void itParsesStartTls() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "STARTTLS");
    assertThat(response.isSupported(Extension.STARTTLS)).isTrue();
  }

  @Test
  public void itParsesPipelining() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "PIPELINING");
    assertThat(response.isSupported(Extension.PIPELINING)).isTrue();
  }

  @Test
  public void itParsesChunking() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "CHUNKING");
    assertThat(response.isSupported(Extension.CHUNKING)).isTrue();
  }

  @Test
  public void itParsesAuth() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "AUTH PLAIN LOGIN");
    assertThat(response.isAuthPlainSupported()).isTrue();
    assertThat(response.isAuthLoginSupported()).isTrue();
  }

  @Test
  public void itParsesSize() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "SIZE");
    assertThat(response.isSupported(Extension.SIZE)).isTrue();
  }

  @Test
  public void itParsesSizeWithASpecifiedSize() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "SIZE 1234000");
    assertThat(response.isSupported(Extension.SIZE)).isTrue();
    assertThat(response.getMaxMessageSize()).contains(1234000L);
  }

  @Test
  public void itIgnoresInvalidSizes() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "SIZE abc");
    assertThat(response.isSupported(Extension.SIZE)).isTrue();
    assertThat(response.getMaxMessageSize()).isEmpty();
  }

  @Test
  public void itIgnoresASizeOfZero() {
    EhloResponse response = parse("smtp.example.com Hello client.example.com", "SIZE 0");
    assertThat(response.isSupported(Extension.SIZE)).isTrue();
    assertThat(response.getMaxMessageSize()).isEmpty();
  }

  private EhloResponse parse(CharSequence... lines) {
    return EhloResponse.parse("", Lists.newArrayList(lines));
  }
}
