package com.velagram.tv;

import java.io.File;
import java.util.*;

public final class CachePolicy {
  public static long trim(File root, long maxBytes, int days) {
    List<File> files = new ArrayList<>();
    collect(root, files);
    files.sort(Comparator.comparingLong(File::lastModified));
    long total = 0,
        removed = 0,
        cutoff = days <= 0 ? 0 : System.currentTimeMillis() - days * 86400000L;
    for (File f : files) total += f.length();
    for (File f : files) {
      long size = f.length();
      if ((cutoff > 0 && f.lastModified() < cutoff) || (maxBytes > 0 && total > maxBytes)) {
        if (f.delete()) {
          total -= size;
          removed += size;
        }
      }
    }
    return removed;
  }

  static void collect(File root, List<File> files) {
    File[] children = root.listFiles();
    if (children == null) return;
    for (File f : children)
      if (f.isDirectory()) collect(f, files);
      else files.add(f);
  }
}
