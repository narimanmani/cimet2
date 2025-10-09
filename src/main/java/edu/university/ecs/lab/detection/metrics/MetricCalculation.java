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

    public static void main(String[] args) {
        String configPath = (args != null && args.length > 0 && args[0] != null && !args[0].isBlank())
                ? args[0]
                : DEFAULT_CONFIG_PATH;

        Config config = ConfigUtil.readConfig(configPath);

        // Create IR of first commit
        createIRSystem(configPath, DEFAULT_IR_PATH);

        // Create Microservice System based on generated IR
        MicroserviceSystem currentSystem = JsonReadWriteUtils.readFromJSON(DEFAULT_IR_PATH, MicroserviceSystem.class);

        // Create SDG
        ServiceDependencyGraph sdg = new ServiceDependencyGraph(currentSystem);

        StringBuilder reportBuilder = new StringBuilder();
        reportBuilder.append(String.format("CIMET ISAR Metrics for %s%n", config.getSystemName()));
        reportBuilder.append(System.lineSeparator());

        // Structural coupling
        StructuralCoupling sc = new StructuralCoupling(sdg);
        appendMetric(reportBuilder, "Maximum Structural Coupling", String.format("%.2f", sc.getMaxSC()));
        appendMetric(reportBuilder, "Average Structural Coupling", String.format("%.2f", sc.getAvgSC()));
        appendMetric(reportBuilder, "Structural Coupling StdDev", String.format("%.2f", sc.getStdSC()));

        // Degree coupling
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

        // Modularity
        ConnectedComponentsModularity m = new ConnectedComponentsModularity(sdg);
        appendMetric(reportBuilder, "Strongly Connected Components", Integer.toString(m.getSCC().size()));
        appendMetric(reportBuilder, "SCC Modularity", String.format("%.2f", m.getModularity()));

        String report = reportBuilder.toString();
        System.out.print(report);
        writeReport(report);
    }

    private static void createIRSystem(String configPath, String outputPath) {
        // Create both directories needed
        FileUtils.makeDirs();

        // Initialize the irExtractionService
        IRExtractionService irExtractionService = new IRExtractionService(configPath, Optional.empty());

        // Generate the Intermediate Representation
        irExtractionService.generateIR(outputPath);
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
