package com.likwiid.ragregulation.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Downloads a model file once and keeps it in the models directory. */
public final class ModelFiles {

    private static final Logger log = LoggerFactory.getLogger(ModelFiles.class);

    private ModelFiles() {
    }

    public static Path ensure(String url, Path target) throws IOException, InterruptedException {
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        log.info("Downloading {} to {}", url, target);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download of " + url + " failed with status " + response.statusCode());
        }
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = response.body()) {
            Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }
}
