package com.velagram.tv;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import java.io.*;
import org.drinkless.tdlib.TdApi;

@androidx.media3.common.util.UnstableApi
public final class TelegramDataSource extends BaseDataSource {
  final Telegram tg;
  Uri uri;
  int id;
  long offset, end;
  volatile boolean closed;

  public TelegramDataSource(Telegram t) {
    super(true);
    tg = t;
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    transferInitializing(spec);
    uri = spec.uri;
    id = Integer.parseInt(uri.getHost());
    offset = spec.position;
    closed = false;
    try {
      TdApi.File f = tg.file(id);
      end = spec.length == C.LENGTH_UNSET ? f.size : Math.min(f.size, offset + spec.length);
      tg.request(id, offset);
      transferStarted(spec);
      return end > 0 ? end - offset : C.LENGTH_UNSET;
    } catch (Exception e) {
      throw new IOException("Telegram stream could not open", e);
    }
  }

  @Override
  public int read(byte[] b, int start, int length) throws IOException {
    if (length == 0) return 0;
    if (end > 0 && offset >= end) return C.RESULT_END_OF_INPUT;
    long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
    long requested = -1;
    while (!closed) {
      try {
        TdApi.File f = tg.file(id);
        long available =
            f.local.isDownloadingCompleted
                ? f.size
                : f.local.downloadOffset + f.local.downloadedPrefixSize;
        boolean contains =
            f.local.isDownloadingCompleted
                || (offset >= f.local.downloadOffset && offset < available);
        if (contains && !f.local.path.isEmpty()) {
          int count = (int) Math.min(length, available - offset);
          if (end > 0) count = (int) Math.min(count, end - offset);
          if (count > 0) {
            try (RandomAccessFile file = new RandomAccessFile(f.local.path, "r")) {
              file.seek(offset);
              int n = file.read(b, start, count);
              if (n > 0) {
                offset += n;
                bytesTransferred(n);
                return n;
              }
            }
          }
        }
        if (requested != offset) {
          tg.request(id, offset);
          requested = offset;
        }
        if (android.os.SystemClock.elapsedRealtime() > deadline)
          throw new IOException("Telegram download timed out");
        Thread.sleep(100);
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("Telegram stream interrupted", e);
      }
    }
    return C.RESULT_END_OF_INPUT;
  }

  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public void close() {
    closed = true;
    if (uri != null) {
      uri = null;
      transferEnded();
    }
  }
}
