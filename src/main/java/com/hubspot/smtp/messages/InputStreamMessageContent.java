package com.hubspot.smtp.messages;

import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * A {@link MessageContent} implementation backed by an {@code InputStream}.
 */
public class InputStreamMessageContent extends MessageContent {

  private static final float UNCOUNTED = -1F;
  private static final float DEFAULT_8BIT_PROPORTION = 0.1F;
  private static final int READ_LIMIT = 8192;

  private final Supplier<InputStream> streamSupplier;
  private final OptionalInt size;
  private final MessageContentEncoding encoding;

  private float eightBitCharProportion = UNCOUNTED;
  private InputStream stream;

  public InputStreamMessageContent(
    Supplier<InputStream> streamSupplier,
    OptionalInt size,
    MessageContentEncoding encoding
  ) {
    this.streamSupplier = streamSupplier;
    this.size = size;
    this.encoding = encoding;
  }

  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  public InputStreamMessageContent(
    ByteSource byteSource,
    OptionalInt size,
    MessageContentEncoding encoding
  ) {
    this(getStream(byteSource), size, encoding);
  }

  @Override
  public OptionalInt size() {
    return size;
  }

  @Override
  public Object getContent() {
    return new CrlfTerminatingChunkedStream(getStream());
  }

  /**
   * Returns an iterator that lazily reads chunks of content from the wrapped stream,
   * ensuring the last is terminated with CRLF.
   */
  @Override
  public Iterator<ByteBuf> getContentChunkIterator(ByteBufAllocator allocator) {
    CrlfTerminatingChunkedStream chunkedStream = new CrlfTerminatingChunkedStream(
      getStream()
    );

    return new Iterator<ByteBuf>() {
      @Override
      public boolean hasNext() {
        try {
          return !chunkedStream.isEndOfInput();
        } catch (Exception e) {
          // isEndOfInput can throw IOException though it declares Exception
          throw new RuntimeException(e);
        }
      }

      @Override
      public ByteBuf next() {
        try {
          return chunkedStream.readChunk(allocator);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  @Override
  public Object getDotStuffedContent() {
    // note: size is hard to predict for dot-stuffed content as
    // the transformation might add a few extra bytes
    return new DotStuffingChunkedStream(getStream());
  }

  @Override
  public MessageContentEncoding getEncoding() {
    return encoding;
  }

  @Override
  public float get8bitCharacterProportion() {
    if (eightBitCharProportion != UNCOUNTED) {
      return eightBitCharProportion;
    }

    int eightBitCharCount = 0;

    InputStream inputStream = getStream();

    if (!inputStream.markSupported()) {
      // if we can't examine the stream non-destructively,
      // assume it has some 8 bit characters, but not enough
      // to require encoding the body as base64
      eightBitCharProportion = DEFAULT_8BIT_PROPORTION;
      return eightBitCharProportion;
    }

    inputStream.mark(READ_LIMIT);

    try {
      int c, bytesRead = 0;

      while (bytesRead < READ_LIMIT && (c = inputStream.read()) != -1) {
        if (0 != (c & 0x80)) {
          eightBitCharCount++;
        }

        bytesRead++;
      }

      inputStream.reset();
      eightBitCharProportion = (1.0F * eightBitCharCount) / bytesRead;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return eightBitCharProportion;
  }

  @Override
  public String getContentAsString() {
    try {
      return CharStreams.toString(
        new InputStreamReader(getStream(), StandardCharsets.UTF_8)
      );
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private InputStream getStream() {
    if (stream == null) {
      stream = streamSupplier.get();
    }

    return stream;
  }

  private static Supplier<InputStream> getStream(ByteSource byteSource) {
    return () -> {
      try {
        return byteSource.openStream();
      } catch (IOException e) {
        throw new RuntimeException("Could not open stream", e);
      }
    };
  }
}
