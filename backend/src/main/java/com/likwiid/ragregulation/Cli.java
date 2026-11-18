package com.likwiid.ragregulation;

import com.likwiid.ragregulation.eval.EvalRunner;
import com.likwiid.ragregulation.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** One-shot commands: {@code ingest} and {@code eval}. */
@Component
public class Cli {

    private static final Logger log = LoggerFactory.getLogger(Cli.class);

    private final IngestService ingestService;
    private final EvalRunner evalRunner;

    public Cli(IngestService ingestService, EvalRunner evalRunner) {
        this.ingestService = ingestService;
        this.evalRunner = evalRunner;
    }

    public int run(String[] args) {
        List<String> options = Arrays.asList(args).subList(1, args.length);
        try {
            switch (args[0]) {
                case "ingest" -> ingestService.ingestAll();
                case "eval" -> evalRunner.run(options);
                default -> throw new IllegalArgumentException("Unknown command " + args[0]);
            }
            return 0;
        } catch (Exception e) {
            log.error("Command {} failed", args[0], e);
            return 1;
        }
    }
}
