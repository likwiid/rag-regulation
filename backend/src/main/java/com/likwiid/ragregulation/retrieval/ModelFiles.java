package com.likwiid.ragregulation.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Downloads a model file once and keeps it in the models directory. Model files are several hundred
 * megabytes, so an interrupted download is resumed with a range request instead of started again,
 * and the file only gets its final name once the full length has arrived.
 */
public final class ModelFiles {

    private static final Logger log = LoggerFactory.getLogger(ModelFiles.class);
    private static final int MAX_ATTEMPTS = 30;

    private ModelFiles() {
    }

    public static Path ensure(String url, Path target) throws IOException, InterruptedException {
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        log.info("Downloading {} to {}", url, target);
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (fetch(client, url, partial)) {
                    Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                    return target;
                }
            } catch (IOException e) {
                last = e;
            }
            log.warn("Download of {} interrupted at {} bytes, attempt {} of {}", url,
                    Files.exists(partial) ? Files.size(partial) : 0, attempt, MAX_ATTEMPTS);
            Thread.sleep(3000);
        }
        throw new IOException("Download of " + url + " did not complete", last);
    }

    /** Appends to the partial file and reports whether the whole file is now present. */
    private static boolean fetch(HttpClient client, String url, Path partial) throws IOException, InterruptedException {
        long have = Files.exists(partial) ? Files.size(partial) : 0;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (have > 0) {
            request.header("Range", "bytes=" + have + "-");
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status == 416) {
            // The server has nothing after our offset: the partial file is already complete.
            response.body().close();
            return have > 0;
        }
        if (status != 200 && status != 206) {
            response.body().close();
            throw new IOException("Download of " + url + " failed with status " + status);
        }
        boolean resumed = status == 206;
        long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        long total = expected < 0 ? -1 : (resumed ? have + expected : expected);
        try (InputStream in = response.body();
             OutputStream out = resumed
                     ? Files.newOutputStream(partial, StandardOpenOption.APPEND)
                     : Files.newOutputStream(partial)) {
            in.transferTo(out);
        }
        return total < 0 || Files.size(partial) == total;
    }
}
