package com.velagram.tv;

import java.io.*;

public final class Streams {
  public static byte[] read(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > limit) throw new IOException("File exceeds the supported size limit");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }
}
