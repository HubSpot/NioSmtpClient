package com.hubspot.smtp.messages;

import com.google.common.io.ByteSource;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.io.InputStream;
import java.util.Iterator;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * The contents of a message, including its headers.
 *
 */
public abstract class MessageContent {

  /**
   * Creates a {@link MessageContent} from a {@code ByteBuf} that might contain eight-bit characters.
   */
  public static MessageContent of(ByteBuf messageBuffer) {
    return of(messageBuffer, MessageContentEncoding.UNKNOWN);
  }

  /**
   * Creates a {@link MessageContent} from a {@code ByteBuf} and specifies its {@link MessageContentEncoding}.
   */
  public static MessageContent of(
    ByteBuf messageBuffer,
    MessageContentEncoding encoding
  ) {
    return new ByteBufMessageContent(messageBuffer, encoding);
  }

  /**
   * Creates a {@link MessageContent} from an {@code InputStream} that might contain eight-bit characters.
   */
  public static MessageContent of(Supplier<InputStream> messageStream) {
    return of(messageStream, MessageContentEncoding.UNKNOWN);
  }

  /**
   * Creates a {@link MessageContent} from a {@code InputStream} and specifies its {@link MessageContentEncoding}.
   */
  public static MessageContent of(
    Supplier<InputStream> messageStream,
    MessageContentEncoding encoding
  ) {
    return new InputStreamMessageContent(messageStream, OptionalInt.empty(), encoding);
  }

  /**
   * Creates a {@link MessageContent} from a {@code InputStream} and specifies its {@link MessageContentEncoding} and size.
   */
  public static MessageContent of(
    Supplier<InputStream> messageStream,
    MessageContentEncoding encoding,
    int size
  ) {
    return new InputStreamMessageContent(messageStream, OptionalInt.of(size), encoding);
  }

  /**
   * Creates a {@link MessageContent} from a {@code ByteSource} that might contain eight-bit characters.
   */
  public static MessageContent of(ByteSource byteSource) {
    return of(byteSource, MessageContentEncoding.UNKNOWN);
  }

  /**
   * Creates a {@link MessageContent} from a {@code ByteSource} and specifies its {@link MessageContentEncoding}.
   */
  public static MessageContent of(
    ByteSource byteSource,
    MessageContentEncoding encoding
  ) {
    OptionalInt size = byteSource
      .sizeIfKnown()
      .transform(s -> OptionalInt.of(Math.toIntExact(s)))
      .or(OptionalInt.empty());
    return new InputStreamMessageContent(byteSource, size, encoding);
  }

  /**
   * The size of the content, used to reject messages for servers that support the SIZE SMTP extension.
   */
  public abstract OptionalInt size();

  // only allow subclasses from this package because only certain objects can be returned from getContent
  MessageContent() {}

  /**
   * Gets the raw message content in a form that can be written to a Netty channel.
   */
  public abstract Object getContent();

  /**
   * Gets an iterator for chunks of content, suitable for use with the chunking SMTP extension.
   *
   */
  public abstract Iterator<ByteBuf> getContentChunkIterator(ByteBufAllocator allocator);

  /**
   * Gets the message content with dot-stuffing applied in a form that can be written to a Netty channel.
   */
  public abstract Object getDotStuffedContent();

  /**
   * Gets the {@link MessageContentEncoding} of the content, indicating whether it
   * contains eight-bit characters.
   */
  public abstract MessageContentEncoding getEncoding();

  /**
   * Estimates the proportion of characters in this {@code MessageContent} that are eight-bit.
   *
   * <p>This proportion can be used to calculate whether a message would be more efficient
   * if encoded as quoted-printable or base64.
   */
  public abstract float get8bitCharacterProportion();

  /**
   * Gets the content interpreted as a UTF-8 string.
   *
   * <p>This is intended for debugging purposes only.
   */
  public abstract String getContentAsString();
}
