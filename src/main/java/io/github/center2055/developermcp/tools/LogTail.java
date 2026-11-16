package io.github.center2055.developermcp.tools;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads the end of a (possibly huge) log file without loading all of it. */
public final class LogTail {

    private LogTail() {
    }

    /**
     * Last {@code lines} lines of the file, optionally only those containing {@code filter} (ignoring case).
     * At most {@code maxBytes} from the end of the file are looked at.
     */
    public static List<String> tail(Path file, int lines, String filter, long maxBytes) throws IOException {
        byte[] bytes;
        boolean truncated;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            truncated = start > 0;
            bytes = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(bytes);
        }
        // Lenient decoding: bad bytes become U+FFFD instead of failing like Files.readAllLines does.
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] all = text.split("\r?\n", -1);
        int first = truncated ? 1 : 0; // the first line may be cut in half
        String needle = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);

        List<String> result = new ArrayList<>();
        for (int i = all.length - 1; i >= first && result.size() < lines; i--) {
            String line = all[i];
            if (i == all.length - 1 && line.isEmpty()) {
                continue; // trailing newline
            }
            if (needle == null || line.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(line);
            }
        }
        java.util.Collections.reverse(result);
        return result;
    }
}
