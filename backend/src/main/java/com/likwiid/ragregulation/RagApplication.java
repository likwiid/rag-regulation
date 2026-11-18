package com.likwiid.ragregulation;

import com.likwiid.ragregulation.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Set;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class RagApplication {

    private static final Set<String> COMMANDS = Set.of("ingest", "eval");

    public static void main(String[] args) {
        boolean cli = args.length > 0 && COMMANDS.contains(args[0]);
        SpringApplication app = new SpringApplication(RagApplication.class);
        if (cli) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = app.run(args);
        if (cli) {
            int code = context.getBean(Cli.class).run(args);
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }
}
