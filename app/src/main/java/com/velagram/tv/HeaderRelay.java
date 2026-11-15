package com.velagram.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import okhttp3.*;

/** Loopback-only HTTP transport for VLC, including headers, range requests and HLS children. */
public final class HeaderRelay implements AutoCloseable {
  final ServerSocket server;
  final String token = UUID.randomUUID().toString();
  final ExecutorService workers = Executors.newCachedThreadPool();
  final Map<String, HttpUrl> origins = new ConcurrentHashMap<>();
  final Map<String, String> originIds = new ConcurrentHashMap<>();
  final Map<String, String> headers;
  final HttpUrl source;
  final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  final Set<Call> calls = ConcurrentHashMap.newKeySet();
  volatile boolean closed;

  public HeaderRelay(String url, Map<String, String> requestHeaders) throws IOException {
    source = HttpUrl.get(url); headers = new HashMap<>(requestHeaders);
    server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    workers.execute(() -> {
      while (!closed) try {
        Socket socket = server.accept(); sockets.add(socket); workers.execute(() -> serve(socket));
      } catch (IOException ignored) { }
    });
  }

  public String url() { return local(source); }
  String local(HttpUrl target) {
    String origin = target.scheme() + "://" + target.host() + ":" + target.port();
    String id = originIds.computeIfAbsent(origin, key -> {
      String value = UUID.randomUUID().toString(); origins.put(value, target); return value;
    });
    return "http://127.0.0.1:" + server.getLocalPort() + "/" + token + "/" + id + target.encodedPath()
        + (target.encodedQuery() == null ? "" : "?" + target.encodedQuery());
  }

  void serve(Socket socket) {
    Call call = null;
    try (Socket connection = socket) {
      connection.setSoTimeout(20000);
      BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
      String line = input.readLine(); if (line == null) return;
      String[] request = line.split(" ");
      if (request.length != 3 || !(request[0].equals("GET") || request[0].equals("HEAD"))) { reject(connection, 405); return; }
      String prefix = "/" + token + "/";
      if (!request[1].startsWith(prefix)) { reject(connection, 403); return; }
      int pathAt = request[1].indexOf('/', prefix.length());
      if (pathAt < 0) { reject(connection, 404); return; }
      HttpUrl origin = origins.get(request[1].substring(prefix.length(), pathAt));
      if (origin == null) { reject(connection, 404); return; }
      String path = request[1].substring(pathAt); int queryAt = path.indexOf('?');
      HttpUrl target = origin.newBuilder().encodedPath(queryAt < 0 ? path : path.substring(0, queryAt))
          .encodedQuery(queryAt < 0 ? null : path.substring(queryAt + 1)).fragment(null).build();
      Request.Builder upstream = new Request.Builder().url(target).header("Accept-Encoding", "identity");
      boolean sameOrigin = target.host().equals(source.host()) && target.port() == source.port() && target.scheme().equals(source.scheme());
      for (Map.Entry<String, String> h : headers.entrySet())
        if (sameOrigin || !h.getKey().matches("(?i)authorization|cookie|x-emby-token")) upstream.header(h.getKey(), h.getValue());
      int lines = 0;
      while ((line = input.readLine()) != null && !line.isEmpty()) {
        if (++lines > 100 || line.length() > 8192) { reject(connection, 431); return; }
        int colon = line.indexOf(':');
        if (colon > 0 && (line.substring(0, colon).equalsIgnoreCase("Range") || line.substring(0, colon).equalsIgnoreCase("If-Range")))
          upstream.header(line.substring(0, colon), line.substring(colon + 1).trim());
      }
      if (request[0].equals("HEAD")) upstream.head();
      call = Api.HTTP.newCall(upstream.build()); calls.add(call);
      try (Response response = call.execute()) {
        if (response.body() == null) { reject(connection, 502); return; }
        String type = response.header("Content-Type", "application/octet-stream");
        boolean hls = type.toLowerCase(Locale.ROOT).contains("mpegurl") || target.encodedPath().endsWith(".m3u8");
        boolean dash = type.toLowerCase(Locale.ROOT).contains("dash+xml") || target.encodedPath().endsWith(".mpd");
        byte[] manifest = null;
        if (hls && !request[0].equals("HEAD") && response.isSuccessful())
          manifest = rewriteHls(new String(Streams.read(response.body().byteStream(), 4 * 1048576), StandardCharsets.UTF_8), response.request().url()).getBytes(StandardCharsets.UTF_8);
        else if (dash && !request[0].equals("HEAD") && response.isSuccessful())
          manifest = rewriteDash(new String(Streams.read(response.body().byteStream(), 4 * 1048576), StandardCharsets.UTF_8), response.request().url()).getBytes(StandardCharsets.UTF_8);
        long length = manifest == null ? response.body().contentLength() : manifest.length;
        OutputStream output = connection.getOutputStream();
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(response.code()).append(' ').append(response.message()).append("\r\nContent-Type: ").append(type).append("\r\nConnection: close\r\n");
        if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n");
        for (String name : new String[] {"Accept-Ranges", "Content-Range", "Cache-Control"})
          if (response.header(name) != null) head.append(name).append(": ").append(response.header(name)).append("\r\n");
        output.write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        if (request[0].equals("HEAD")) return;
        if (manifest != null) output.write(manifest);
        else {
          InputStream body = response.body().byteStream(); byte[] buffer = new byte[65536]; int n;
          while (!closed && (n = body.read(buffer)) != -1) output.write(buffer, 0, n);
        }
      }
    } catch (Exception ignored) { }
    finally { sockets.remove(socket); if (call != null) calls.remove(call); }
  }

  String rewriteDash(String manifest, HttpUrl base) {
    // Relative DASH paths already retain their original base path through the relay.
    // Rewrite only absolute resource URLs, leaving XML namespaces and templates intact.
    Matcher matcher = Pattern.compile("(<(?:[\\w-]+:)?BaseURL(?:\\s[^>]*)?>)(https?://[^<]+)(</(?:[\\w-]+:)?BaseURL>)|(\\b(?:media|initialization|sourceURL|index)=[\"'])(https?://[^\"']+)([\"'])").matcher(manifest);
    StringBuffer output = new StringBuffer();
    while (matcher.find()) {
      boolean element = matcher.group(1) != null;
      String raw = matcher.group(element ? 2 : 5);
      HttpUrl target = base.resolve(org.jsoup.parser.Parser.unescapeEntities(raw, true));
      String replacement = target == null ? matcher.group() : matcher.group(element ? 1 : 4) + local(target).replace("&", "&amp;") + matcher.group(element ? 3 : 6);
      matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(output); return output.toString();
  }

  String rewriteHls(String manifest, HttpUrl base) {
    StringBuilder output = new StringBuilder();
    for (String line : manifest.split("\\r?\\n", -1)) {
      if (line.startsWith("#")) {
        Matcher matcher = Pattern.compile("URI=\"([^\"]+)\"").matcher(line); StringBuffer changed = new StringBuffer();
        while (matcher.find()) {
          HttpUrl target = base.resolve(matcher.group(1));
          matcher.appendReplacement(changed, Matcher.quoteReplacement(target == null ? matcher.group() : "URI=\"" + local(target) + "\""));
        }
        matcher.appendTail(changed); output.append(changed);
      } else if (!line.trim().isEmpty()) {
        HttpUrl target = base.resolve(line.trim()); output.append(target == null ? line : local(target));
      }
      output.append('\n');
    }
    return output.toString();
  }

  static void reject(Socket socket, int code) throws IOException {
    socket.getOutputStream().write(("HTTP/1.1 " + code + " Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
  }

  public void close() {
    closed = true;
    try { server.close(); } catch (IOException ignored) { }
    for (Call call : calls) call.cancel();
    for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
    workers.shutdownNow();
  }
}
