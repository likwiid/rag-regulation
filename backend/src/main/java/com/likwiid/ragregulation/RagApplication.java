package com.likwiid.ragregulation;

import com.likwiid.ragregulation.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import com.likwiid.ragregulation.retrieval.ModelFiles;

import java.nio.file.Path;

import java.util.Set;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class RagApplication {

    private static final Set<String> COMMANDS = Set.of("ingest", "eval");

    public static void main(String[] args) {
        boolean cli = args.length > 0 && COMMANDS.contains(args[0]);
        SpringApplication app = new SpringApplication(RagApplication.class);
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                ensureEmbeddingModel(event.getEnvironment()));
        if (cli) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = app.run(args);
        if (cli) {
            int code = context.getBean(Cli.class).run(args);
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }

    /** The embedding model bean loads its files at start-up, so they have to be on disk before the context starts. */
    private static void ensureEmbeddingModel(Environment env) {
        Path dir = Path.of(env.getRequiredProperty("rag.models-dir"), "embedding");
        try {
            ModelFiles.ensure(env.getRequiredProperty("rag.embedding.model-url"), dir.resolve("model.onnx"));
            ModelFiles.ensure(env.getRequiredProperty("rag.embedding.tokenizer-url"), dir.resolve("tokenizer.json"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while downloading the embedding model", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not download the embedding model", e);
        }
    }
}
