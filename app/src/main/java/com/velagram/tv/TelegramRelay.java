package com.velagram.tv;

import android.net.Uri;
import androidx.media3.datasource.DataSpec;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public final class TelegramRelay implements AutoCloseable {
  final ServerSocket server;
  final Telegram tg;
  final String token = UUID.randomUUID().toString();
  final ExecutorService pool = Executors.newCachedThreadPool();
  volatile boolean closed;

  public TelegramRelay(Telegram t) throws IOException {
    tg = t;
    server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    pool.execute(
        () -> {
          while (!closed)
            try {
              Socket s = server.accept();
              pool.execute(() -> serve(s));
            } catch (IOException ignored) {
            }
        });
  }

  public String url(int id) {
    return "http://127.0.0.1:" + server.getLocalPort() + "/" + token + "/" + id;
  }

  void serve(Socket socket) {
    TelegramDataSource source = null;
    try (Socket s = socket) {
      s.setSoTimeout(10000);
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(
                  s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
      String line = in.readLine();
      if (line == null) return;
      String[] request = line.split(" ");
      if (request.length < 2 || !request[1].startsWith("/" + token + "/")) {
        s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes());
        return;
      }
      int id = Integer.parseInt(request[1].substring(request[1].lastIndexOf('/') + 1));
      long size = tg.file(id).size, start = 0, end = size - 1;
      boolean partial = false;
      while ((line = in.readLine()) != null && !line.isEmpty())
        if (line.toLowerCase(Locale.ROOT).startsWith("range: bytes=")) {
          String range = line.substring(line.indexOf('=') + 1).trim();
          String[] parts = range.split("-", 2);
          start = Long.parseLong(parts[0]);
          if (parts.length > 1 && !parts[1].isEmpty())
            end = Math.min(end, Long.parseLong(parts[1]));
          partial = true;
        }
      if (start >= size || end < start) {
        s.getOutputStream()
            .write(
                ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"
                        + size
                        + "\r\nContent-Length: 0\r\n\r\n")
                    .getBytes());
        return;
      }
      OutputStream out = s.getOutputStream();
      String headers =
          "HTTP/1.1 "
              + (partial ? "206 Partial Content" : "200 OK")
              + "\r\n"
              + "Accept-Ranges: bytes\r\n"
              + "Content-Type: application/octet-stream\r\n"
              + "Content-Length: "
              + (end - start + 1)
              + "\r\n"
              + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "")
              + "Connection: close\r\n\r\n";
      out.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      if (request[0].equals("HEAD")) return;
      source = new TelegramDataSource(tg);
      source.open(
          new DataSpec.Builder()
              .setUri(Uri.parse("td://" + id))
              .setPosition(start)
              .setLength(end - start + 1)
              .build());
      byte[] b = new byte[65536];
      int n;
      while (!closed && (n = source.read(b, 0, b.length)) > 0) {
        out.write(b, 0, n);
        out.flush();
      }
    } catch (Exception ignored) {
    } finally {
      if (source != null) source.close();
    }
  }

  public void close() {
    closed = true;
    try {
      server.close();
    } catch (Exception ignored) {
    }
    pool.shutdownNow();
  }
}
