package org.synergyst.minutiae.chat;

import org.synergyst.minutiae.storage.ChatTranscriptRow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a persisted chat transcript to RFC 4180 comma-separated values.
 *
 * <p>The output carries a header row followed by one row per line, with columns
 * {@code seq}, {@code epoch_ms}, {@code iso}, and {@code message}. Each field is
 * quoted when it contains a comma, quote, or newline, and embedded quotes are
 * doubled. Fields are never interpreted as formulae or markup; the renderer is a
 * pure function of its input and performs a single linear pass per field.
 */
public final class TranscriptCsv {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private TranscriptCsv() {
    }

    /**
     * Renders transcript rows to CSV text.
     *
     * @param rows the transcript rows, in ascending order
     * @return the CSV document; header only when the input is empty
     */
    public static String render(final List<ChatTranscriptRow> rows) {
        final StringBuilder sb = new StringBuilder(64 + rows.size() * 48);
        sb.append("seq,epoch_ms,iso,message\n");
        for (final ChatTranscriptRow r : rows) {
            sb.append(r.seq()).append(',')
                    .append(r.ts()).append(',')
                    .append(field(ISO.format(Instant.ofEpochMilli(r.ts())))).append(',')
                    .append(field(r.body())).append('\n');
        }
        return sb.toString();
    }

    private static String field(final String raw) {
        final String s = raw == null ? "" : raw;
        boolean quote = false;
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                quote = true;
                break;
            }
        }
        if (!quote) {
            return s;
        }
        final StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            final char c = s.charAt(i);
            if (c == '"') {
                sb.append('"');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}