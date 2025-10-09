package edu.university.ecs.lab.detection.metrics;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.models.sdg.ServiceDependencyGraph;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.detection.metrics.models.ConnectedComponentsModularity;
import edu.university.ecs.lab.detection.metrics.models.DegreeCoupling;
import edu.university.ecs.lab.detection.metrics.models.StructuralCoupling;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Calculates structural, degree coupling metrics, and modularity metrics for a microservice system based on service dependency graph (SDG)
 */
public class MetricCalculation {

    private static final String DEFAULT_CONFIG_PATH = "./config.json";
    private static final String DEFAULT_IR_PATH = "./output/IR.json";
    private static final String METRICS_OUTPUT_PATH = "./output/metrics-summary.txt";
    private static final int SHORT_COMMIT_LENGTH = 12;

    public static void main(String[] args) {
        String configPath = resolveConfigPath(args);
        Config config = ConfigUtil.readConfig(configPath);

        StringBuilder reportBuilder = new StringBuilder();
        reportBuilder.append(String.format("CIMET ISAR Metrics for %s%n", config.getSystemName()));

        String[] commitArgs = collectCommitArgs(args);

        for (int index = 0; index < commitArgs.length; index++) {
            Optional<String> commitId = sanitizeCommit(commitArgs[index]);

            MicroserviceSystem currentSystem = createIRSystem(configPath, DEFAULT_IR_PATH, commitId);
            String resolvedCommit = Optional.ofNullable(currentSystem.getCommitID())
                    .filter(id -> !id.isBlank())
                    .orElseGet(() -> commitId.orElse("HEAD"));
            ServiceDependencyGraph sdg = new ServiceDependencyGraph(currentSystem);

            if (index > 0) {
                reportBuilder.append(System.lineSeparator());
            }

            String commitLabel = shortCommit(resolvedCommit);
            reportBuilder.append(System.lineSeparator());
            reportBuilder.append(String.format("Commit: %s%n", resolvedCommit));
            reportBuilder.append(String.format("Display Commit: %s%n", commitLabel));

            appendStructuralCoupling(reportBuilder, sdg);
            appendDegreeCoupling(reportBuilder, sdg);
            appendModularity(reportBuilder, sdg);
        }

        String report = reportBuilder.toString();
        System.out.print(report);
        writeReport(report);
    }

    private static String resolveConfigPath(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return DEFAULT_CONFIG_PATH;
        }
        return args[0];
    }

    private static String[] collectCommitArgs(String[] args) {
        if (args == null || args.length <= 1) {
            return new String[]{null};
        }

        String[] commits = new String[args.length - 1];
        System.arraycopy(args, 1, commits, 0, commits.length);
        return commits;
    }

    private static Optional<String> sanitizeCommit(String commit) {
        if (commit == null) {
            return Optional.empty();
        }

        String trimmed = commit.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(trimmed);
    }

    private static String shortCommit(String commit) {
        if (commit.length() <= SHORT_COMMIT_LENGTH) {
            return commit;
        }
        return commit.substring(0, SHORT_COMMIT_LENGTH);
    }

    private static MicroserviceSystem createIRSystem(String configPath, String outputPath, Optional<String> commitId) {
        // Create both directories needed
        FileUtils.makeDirs();

        // Initialize the irExtractionService
        IRExtractionService irExtractionService = new IRExtractionService(configPath, commitId);

        // Generate the Intermediate Representation
        irExtractionService.generateIR(outputPath);

        // Create Microservice System based on generated IR
        return JsonReadWriteUtils.readFromJSON(outputPath, MicroserviceSystem.class);
    }

    private static void appendStructuralCoupling(StringBuilder reportBuilder, ServiceDependencyGraph sdg) {
        StructuralCoupling sc = new StructuralCoupling(sdg);
        appendMetric(reportBuilder, "Maximum Structural Coupling", String.format("%.2f", sc.getMaxSC()));
        appendMetric(reportBuilder, "Average Structural Coupling", String.format("%.2f", sc.getAvgSC()));
        appendMetric(reportBuilder, "Structural Coupling StdDev", String.format("%.2f", sc.getStdSC()));
    }

    private static void appendDegreeCoupling(StringBuilder reportBuilder, ServiceDependencyGraph sdg) {
        DegreeCoupling dc = new DegreeCoupling(sdg);
        appendMetric(reportBuilder, "Maximum AIS", Integer.toString(dc.getMaxAIS()));
        appendMetric(reportBuilder, "Average AIS", String.format("%.2f", dc.getAvgAIS()));
        appendMetric(reportBuilder, "AIS StdDev", String.format("%.2f", dc.getStdAIS()));

        appendMetric(reportBuilder, "Maximum ADS", Integer.toString(dc.getMaxADS()));
        appendMetric(reportBuilder, "Average ADS", String.format("%.2f", dc.getADCS()));
        appendMetric(reportBuilder, "ADS StdDev", String.format("%.2f", dc.getStdADS()));

        appendMetric(reportBuilder, "Maximum ACS", Integer.toString(dc.getMaxACS()));
        appendMetric(reportBuilder, "Average ACS", String.format("%.2f", dc.getADCS()));
        appendMetric(reportBuilder, "ACS StdDev", String.format("%.2f", dc.getStdACS()));

        appendMetric(reportBuilder, "Service Coupling Factor", String.format("%.2f", dc.getSCF()));
        appendMetric(reportBuilder, "Service Interdependence", Integer.toString(dc.getSIY()));
    }

    private static void appendModularity(StringBuilder reportBuilder, ServiceDependencyGraph sdg) {
        ConnectedComponentsModularity m = new ConnectedComponentsModularity(sdg);
        appendMetric(reportBuilder, "Strongly Connected Components", Integer.toString(m.getSCC().size()));
        appendMetric(reportBuilder, "SCC Modularity", String.format("%.2f", m.getModularity()));
    }

    private static void appendMetric(StringBuilder builder, String name, String value) {
        builder.append(String.format("%s: %s%n", name, value));
    }

    private static void writeReport(String report) {
        try {
            Files.writeString(Path.of(METRICS_OUTPUT_PATH), report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write metrics summary", e);
        }
    }
}
