package com.hubspot.smtp.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;

public class InputStreamMessageContent extends MessageContent {
  private static final int UNCOUNTED = -1;
  private static final int READ_LIMIT = 8192;

  private final Supplier<InputStream> streamSupplier;
  private final int size;
  private final MessageContentEncoding encoding;

  private int eightBitCharCount = UNCOUNTED;
  private InputStream stream;

  public InputStreamMessageContent(Supplier<InputStream> streamSupplier, int size, MessageContentEncoding encoding) {
    this.streamSupplier = streamSupplier;
    this.size = size;
    this.encoding = encoding;
  }

  public InputStreamMessageContent(ByteSource byteSource, int size, MessageContentEncoding encoding) {
    this(getStream(byteSource), size, encoding);
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public Object getContent() {
    return new CrlfTerminatingChunkedStream(getStream());
  }

  @Override
  public Object getDotStuffedContent() {
    // note: size is hard to predict for dot-stuffed content as
    // the transformation might add a few extra bytes
    return new DotStuffingChunkedStream(getStream(), size);
  }

  @Override
  public MessageContentEncoding getEncoding() {
    return encoding;
  }

  @Override
  public int count8bitCharacters() {
    if (eightBitCharCount != UNCOUNTED) {
      return eightBitCharCount;
    }

    eightBitCharCount = 0;

    InputStream inputStream = getStream();
    inputStream.mark(READ_LIMIT);

    try {
      int c, bytesRead = 0;

      while (bytesRead < READ_LIMIT && (c = inputStream.read()) != -1) {
        if (0 != (c & 0x80)) {
          eightBitCharCount++;
        }

        bytesRead++;
      }

      // if we've already read READ_LIMIT, estimate the remaining stream
      if (bytesRead == READ_LIMIT) {
        eightBitCharCount *= size / READ_LIMIT;
      }

      inputStream.reset();

    } catch (IOException e) {
      e.printStackTrace();
    }

    return eightBitCharCount;
  }

  @Override
  public String getContentAsString() {
    try {
      return CharStreams.toString(new InputStreamReader(getStream(), StandardCharsets.UTF_8));
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
