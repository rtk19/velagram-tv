package com.velagram.tv;

import java.io.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public final class Guide {
  public static class Program {
    public String channel, title, description;
    public long start, end;
  }

  public static long date(String value) throws Exception {
    String s = value.trim();
    java.text.SimpleDateFormat f =
        new java.text.SimpleDateFormat(
            s.contains(" ") ? "yyyyMMddHHmmss Z" : "yyyyMMddHHmmss", Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    f.setLenient(false);
    return f.parse(s).getTime();
  }

  public static List<Program> parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setExpandEntityReferences(false);
    Document d =
        f.newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    NodeList nodes = d.getElementsByTagName("programme");
    List<Program> r = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      Element e = (Element) nodes.item(i);
      try {
        Program p = new Program();
        p.channel = e.getAttribute("channel");
        p.start = date(e.getAttribute("start"));
        p.end = date(e.getAttribute("stop"));
        NodeList titles = e.getElementsByTagName("title"), desc = e.getElementsByTagName("desc");
        p.title = titles.getLength() > 0 ? titles.item(0).getTextContent() : "Program";
        p.description = desc.getLength() > 0 ? desc.item(0).getTextContent() : "";
        r.add(p);
      } catch (Exception ignored) {
      }
    }
    return r;
  }
}
