package dev.rewindamp;

import java.net.URLEncoder;

final class UrlEncoding {
    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(")
                .replace("%29", ")").replace("%7E", "~");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
