package edu.university.ecs.lab.detection;

import com.google.gson.JsonArray;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.models.sdg.MethodDependencyGraph;
import edu.university.ecs.lab.common.models.sdg.ServiceDependencyGraph;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.SystemChange;
import edu.university.ecs.lab.delta.services.DeltaExtractionService;
import edu.university.ecs.lab.detection.antipatterns.services.*;
import edu.university.ecs.lab.detection.architecture.models.*;
import edu.university.ecs.lab.detection.architecture.services.ARDetectionService;
import edu.university.ecs.lab.detection.metrics.RunCohesionMetrics;
import edu.university.ecs.lab.detection.metrics.models.ConnectedComponentsModularity;
import edu.university.ecs.lab.detection.metrics.models.DegreeCoupling;
import edu.university.ecs.lab.detection.metrics.models.StructuralCoupling;
import edu.university.ecs.lab.detection.metrics.services.MetricResultCalculation;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * Service class for detection of antipatterns, architectural rule violations, and metrics
 */
public class DetectionService {

    /**
     * Column labels for violation counts and metrics
     */
    private static final String[] columnLabels = new String[]{"Commit ID", "Commit Date", "Greedy Microservices", "Hub-like Microservices", "Service Chains",
            "Wrong Cuts", "Cyclic Dependencies", "Wobbly Service Interactions",  "No Healthchecks",
            "No API Gateway", "maxAIS", "avgAIS", "stdAIS", "maxADS", "ADCS", "stdADS", "maxACS", "avgACS", "stdACS", "SCF", "SIY", "maxSC", "avgSC",
            "stdSC", "SCCmodularity", "maxSIDC", "avgSIDC", "stdSIDC", "maxSSIC", "avgSSIC", "stdSSIC",
            "maxLOMLC", "avgLOMLC", "stdLOMLC"};

    private static final WeekFields WEEK_FIELDS = WeekFields.ISO;
    
    /**
     * Count of antipatterns, metrics, and architectural rules
     */
    private static final int ANTIPATTERNS = 8;
    private static final int METRICS = 24;
    private static final int ARCHRULES = 4;

    private static String BASE_DELTA_PATH = "/Delta/Delta";
    private static String BASE_IR_PATH = "/IR/IR";



    /**
     * Detection services and parameters
     */
    private final String configPath;
    private final Config config;
    private final GitService gitService;
    private IRExtractionService irExtractionService;
    private ARDetectionService arDetectionService;
    private DeltaExtractionService deltaExtractionService;
    private MergeService mergeService;
    private final XSSFWorkbook workbook;
    private XSSFSheet sheet;
    private final LocalDate analysisStartDate;
    private final LocalDate analysisEndDate;
//    private final String firstCommitID

    /**
     * Construct with given configuration file path
     * @param configPath YAML file to extract microservice details from
     */
    public DetectionService(String configPath) {
        this.configPath = configPath;
        // Read in config
        config = ConfigUtil.readConfig(configPath);
        // Setup dirs
        FileUtils.makeDirs();
        // Setup local repo
        gitService = new GitService(configPath);
        analysisStartDate = gitService.getStartDate().orElse(null);
        analysisEndDate = gitService.getEndDate().orElse(null);
        workbook = new XSSFWorkbook();

        BASE_DELTA_PATH = "./output/" + config.getRepoName() + BASE_DELTA_PATH;
        BASE_IR_PATH = "./output/" + config.getRepoName() + BASE_IR_PATH;
    }

    /**
     * Method to detect antipatterns, architectural rule violations, and metrics
     */
    public void runDetection() {

        // Get list of commits
        Iterable<RevCommit> iterable = gitService.getLog();
        List<RevCommit> commits = iterableToList(iterable);

        // Generate the initial IR
        irExtractionService = new IRExtractionService(configPath, Optional.of(commits.get(0).toString().split(" ")[1]));
        String firstCommit = commits.get(0).getName().substring(0, 4);
        irExtractionService.generateIR(BASE_IR_PATH + "1_" + firstCommit + ".json");

        // Setup sheet and headers
        sheet = workbook.createSheet(config.getSystemName());
        writeHeaders();

        // Write the initial row as empty
        writeEmptyRow(1);

        Map<LocalDate, WeeklySummary> weeklySummaries = initializeWeeklySummaries();

        // Starting at the first commit until commits - 1
        for (int i = 0; i < commits.size(); i++) {
            MicroserviceSystem newSystem = null;
            SystemChange systemChange = null;

            // Old commit = curr, new commit = next
            String commitIdOld = commits.get(i).toString().split(" ")[1];

            int currIndex = i + 1, nextIndex = i + 2;

            // Fill the next row as empty for future use
            if(i < commits.size() - 1) {
                writeEmptyRow(nextIndex);
            }

            // Get instance of our current row
            Row row = sheet.getRow(currIndex);

            // Set the commitID as the first cell value
            Cell commitIdCell = row.createCell(0);
            commitIdCell.setCellValue(commitIdOld);

            // Set the commit date as the second cell value
            LocalDate commitDate = Instant.ofEpochSecond(commits.get(i).getCommitTime())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
            Cell commitDateCell = row.createCell(1);
            commitDateCell.setCellValue(commitDate.toString());

            // Read in the old system
            String oldIRPath = BASE_IR_PATH + (i+1) + "_" + commitIdOld.substring(0, 4) +".json";
            MicroserviceSystem oldSystem = sanitizeMicroserviceSystem(
                    JsonReadWriteUtils.readFromJSON(oldIRPath, MicroserviceSystem.class));

            // Extract changes from one commit to the other
            if(i < commits.size() - 1) {
                String commitIdNew = commits.get(i + 1).toString().split(" ")[1];
                String newIRPath = BASE_IR_PATH + (i+2) + "_" + commitIdNew.substring(0, 4) +".json";
                String deltaPath = BASE_DELTA_PATH + (i+1) + "_" + commitIdOld.substring(0, 4) + "_" + commitIdNew.substring(0, 4) + ".json";

                deltaExtractionService = new DeltaExtractionService(configPath, deltaPath, commitIdOld, commitIdNew);
                deltaExtractionService.generateDelta();

                // Merge Delta changes to old IR to create new IR representing new commit changes
                MergeService mergeService = new MergeService(oldIRPath, deltaPath, configPath, newIRPath);
                mergeService.generateMergeIR(commitIdNew.substring(0, 4));

                // Read in the new system and system change
                newSystem = sanitizeMicroserviceSystem(
                        JsonReadWriteUtils.readFromJSON(newIRPath, MicroserviceSystem.class));
                systemChange = JsonReadWriteUtils.readFromJSON(deltaPath, SystemChange.class);
            }

            // Init all the lists/maps
            List<AbstractAR> rules = new ArrayList<>();
            Map<String, Integer> antipatterns = createDefaultAntipatternMap();
            Map<String, Double> metrics = createDefaultMetricMap();

            // We can detect/update if there are >= 1 microservices
            if (Objects.nonNull(oldSystem.getMicroservices()) && !oldSystem.getMicroservices().isEmpty()) {
                detectAntipatterns(oldSystem, antipatterns);
                detectMetrics(oldSystem, metrics, oldIRPath);

                updateAntiPatterns(currIndex, antipatterns);
                updateMetrics(currIndex, metrics);
            }

            updateWeeklySummary(weeklySummaries, commitDate, antipatterns, metrics);

            // For simplicity we will skip rules on the last iteration since there is no newSystem
//            if(i < commits.size() - 1) {
//                arDetectionService = new ARDetectionService(systemChange, oldSystem, newSystem);
//                rules = arDetectionService.scanUseCases();
//
//                updateRules(nextIndex, rules);
//            }

            // After completing this iteration, we can replace oldIR with newIR
            // try {
            //     Files.move(Paths.get(NEW_IR_PATH), Paths.get(OLD_IR_PATH), StandardCopyOption.REPLACE_EXISTING);
            // } catch (IOException e) {
            //     e.printStackTrace();
            //     System.exit(1);
            // }
        }

        writeWeeklyAnalysis(weeklySummaries);

        // At the end we write the workbook to file
        try (FileOutputStream fileOut = new FileOutputStream(String.format("./output/%s/output-%s.xlsx",config.getRepoName(), config.getSystemName()))) {
            workbook.write(fileOut);
//            System.out.printf("Excel file created: AntiPatterns_%s.xlsx%n", config.getSystemName());
            workbook.close();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);

        }

    }

    /**
     * Write headers to XSSFSheet
     */
    private void writeHeaders() {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < columnLabels.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columnLabels[i]);
        }
    }

    /**
     * Write empty row to XSSFSheet
     * 
     * @param rowIndex index to write row to
     */
    private void writeEmptyRow(int rowIndex) {
        Row row = sheet.createRow(rowIndex);
        for(int i = 0; i < columnLabels.length; i++) {
            if (i == 0 || i == 1) {
                row.createCell(i).setCellValue("");
            } else {
                row.createCell(i).setCellValue(0);
            }
        }
    }

    /**
     * Convert iterable to a list
     * 
     * @param iterable iterable object to convert
     * @return iterable object converted to a list
     */
    private List<RevCommit> iterableToList(Iterable<RevCommit> iterable) {
        Iterator<RevCommit> iterator = iterable.iterator();
        List<RevCommit> list = new LinkedList<>();
        while (iterator.hasNext()) {
            list.add(iterator.next());
        }
        Collections.reverse(list);

        return list;
    }

    /**
     * Detect antipatterns in the given microservice
     * 
     * @param microserviceSystem microservice to scan for antipatterns
     * @param allAntiPatterns map of antipattern names and integers to store antipattern counts
     */
    private void detectAntipatterns(MicroserviceSystem microserviceSystem, Map<String, Integer> allAntiPatterns) {

        ServiceDependencyGraph sdg = new ServiceDependencyGraph(microserviceSystem);
        MethodDependencyGraph mdg = new MethodDependencyGraph(microserviceSystem);

        // KEYS must match columnLabels field
        allAntiPatterns.put("Greedy Microservices", new GreedyService().getGreedyMicroservices(sdg).numGreedyMicro());
        allAntiPatterns.put("Hub-like Microservices", new HubLikeService().getHubLikeMicroservice(sdg).numHubLike());
        allAntiPatterns.put("Service Chains", new ServiceChainMSLevelService().getServiceChains(sdg).numServiceChains());
//        allAntiPatterns.put("Service Chains (Method level)", new ServiceChainMethodLevelService().getServiceChains(mdg).numServiceChains());
        allAntiPatterns.put("Wrong Cuts", new WrongCutsService().detectWrongCuts(microserviceSystem).numWrongCuts());
        allAntiPatterns.put("Cyclic Dependencies", new CyclicDependencyMSLevelService().findCyclicDependencies(sdg).numCyclicDep());
//        allAntiPatterns.put("Cyclic Dependencies (Method level)", new CyclicDependencyMethodLevelService().findCyclicDependencies(mdg).numCyclicDep());
        allAntiPatterns.put("Wobbly Service Interactions", new WobblyServiceInteractionService().findWobblyServiceInteractions(microserviceSystem).numWobbblyService());
        allAntiPatterns.put("No Healthchecks", new NoHealthcheckService().checkHealthcheck(microserviceSystem).numNoHealthChecks());
        allAntiPatterns.put("No API Gateway", new NoApiGatewayService().checkforApiGateway(microserviceSystem).getBoolApiGateway());

    }

    private void detectMetrics(MicroserviceSystem microserviceSystem, Map<String, Double> metrics, String oldIRPath) {

        // Create SDG
        ServiceDependencyGraph sdg = new ServiceDependencyGraph(microserviceSystem);
        
        // Degree Coupling
        DegreeCoupling dc = new DegreeCoupling(sdg);
        metrics.put("maxAIS", (double) dc.getMaxAIS());
        metrics.put("avgAIS", dc.getAvgAIS());
        metrics.put("stdAIS", dc.getStdAIS());
        metrics.put("maxADS", (double) dc.getMaxADS());
        metrics.put("ADCS", dc.getADCS());
        metrics.put("stdADS", dc.getStdADS());
        metrics.put("maxACS", (double) dc.getMaxACS());
        metrics.put("avgACS", dc.getAvgACS());
        metrics.put("stdACS", dc.getStdACS());
        metrics.put("SCF", dc.getSCF());
        metrics.put("SIY", (double) dc.getSIY());
        
        // Structural Coupling
        StructuralCoupling sc = new StructuralCoupling(sdg);
        metrics.put("maxSC", sc.getMaxSC());
        metrics.put("avgSC", sc.getAvgSC());
        metrics.put("stdSC", sc.getStdSC());
        
        // Modularity
        ConnectedComponentsModularity mod = new ConnectedComponentsModularity(sdg);
        metrics.put("SCCmodularity", mod.getModularity());

        MetricResultCalculation cohesionMetrics = RunCohesionMetrics.calculateCohesionMetrics(oldIRPath);

        metrics.put("maxSIDC", cohesionMetrics.getMax("ServiceInterfaceDataCohesion"));
        metrics.put("avgSIDC", cohesionMetrics.getAverage("ServiceInterfaceDataCohesion"));
        metrics.put("stdSIDC", cohesionMetrics.getStdDev("ServiceInterfaceDataCohesion"));
        metrics.put("maxSSIC", cohesionMetrics.getMax("StrictServiceImplementationCohesion"));
        metrics.put("avgSSIC", cohesionMetrics.getAverage("StrictServiceImplementationCohesion"));
        metrics.put("stdSSIC", cohesionMetrics.getStdDev("StrictServiceImplementationCohesion"));
        metrics.put("maxLOMLC", cohesionMetrics.getMax("LackOfMessageLevelCohesion"));
        metrics.put("avgLOMLC", cohesionMetrics.getAverage("LackOfMessageLevelCohesion"));
        metrics.put("stdLOMLC", cohesionMetrics.getStdDev("LackOfMessageLevelCohesion"));

    }

    /**
     * Update counts of architectural rule violations in excel
     * 
     * @param rowIndex XSSFSheet row index
     * @param currARs list of architectural rule violations
     */
    private void updateRules(int rowIndex, List<AbstractAR> currARs) {
        int[] arcrules_counts = new int[ARCHRULES];
        Arrays.fill(arcrules_counts, 0);

        // Increment appropriate archrule count
        if (currARs != null && !currARs.isEmpty()) {
            for (AbstractAR archRule : currARs) {
                if (archRule instanceof AR3) {
                    arcrules_counts[0]++;
                } else if (archRule instanceof AR4) {
                    arcrules_counts[1]++;
                } else if (archRule instanceof AR6) {
                    arcrules_counts[2]++;
                } else if (archRule instanceof AR7) {
                    arcrules_counts[3]++;
                }
            }
        }

        // Update architectural rule counts in XSSFSheet
        Row row = sheet.getRow(rowIndex);
        for (int i = 0; i < arcrules_counts.length; i++) {
            Cell cell = row.getCell(i + 2 + ANTIPATTERNS + METRICS); // first two columns are for commit ID and date + rest for anti-patterns+metrics
            cell.setCellValue(arcrules_counts[i]);
        }
    }

    /**
     * Update antipattern counts in excel
     * 
     * @param rowIndex XSSFSheet row index
     * @param allAntiPatterns map of antipatterns to integer count
     */
    private void updateAntiPatterns(int rowIndex, Map<String, Integer> allAntiPatterns) {
        Row row = sheet.getRow(rowIndex);

        for (int i = 0; i < ANTIPATTERNS; i++) {
            int offset = i + 2; // i + 2 because the first two columns are for commit ID and date
            Cell cell = row.getCell(offset);

            // Default value for No API Gateway is 1, meaning true
            cell.setCellValue(
                    allAntiPatterns.getOrDefault(
                            columnLabels[offset],
                            "No API Gateway".equals(columnLabels[offset]) ? 1 : 0
                    ));
        }
    }

    /**
     * Update metric counts in excel
     * 
     * @param rowIndex XSSFSheet row index
     * @param metrics map of metrics to double value
     */
    private void updateMetrics(int rowIndex, Map<String, Double> metrics) {
        Row row = sheet.getRow(rowIndex);

        for (int i = 0; i < METRICS; i++) {
            int offset = i + 2 + ANTIPATTERNS; // first two columns are for commit ID and date + rest for anti-patterns
            Cell cell = row.getCell(offset);
            cell.setCellValue(metrics.getOrDefault(columnLabels[offset],0.0));
        }
    }

    /**
     * Create a map containing default anti-pattern values.
     *
     * @return map of anti-pattern labels to default values
     */
    private Map<String, Integer> createDefaultAntipatternMap() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        for (int i = 0; i < ANTIPATTERNS; i++) {
            String label = columnLabels[i + 2];
            defaults.put(label, "No API Gateway".equals(label) ? 1 : 0);
        }
        return defaults;
    }

    /**
     * Create a map containing default metric values.
     *
     * @return map of metric labels to default values
     */
    private Map<String, Double> createDefaultMetricMap() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        for (int i = 0; i < METRICS; i++) {
            defaults.put(columnLabels[i + 2 + ANTIPATTERNS], 0.0);
        }
        return defaults;
    }

    /**
     * Prepare weekly summaries for each requested week so empty intervals appear in the report.
     *
     * @return map keyed by week start date
     */
    private Map<LocalDate, WeeklySummary> initializeWeeklySummaries() {
        TreeMap<LocalDate, WeeklySummary> summaries = new TreeMap<>();

        if (Objects.nonNull(analysisStartDate) && Objects.nonNull(analysisEndDate)) {
            LocalDate current = analysisStartDate;
            while (!current.isAfter(analysisEndDate)) {
                LocalDate weekEnd = calculateWeekEnd(current);
                summaries.put(current, new WeeklySummary(current, weekEnd));
                current = current.plusWeeks(1);
            }
        }

        return summaries;
    }

    /**
     * Update the weekly aggregation data with a commit's metrics.
     *
     * @param weeklySummaries map storing aggregated values by week
     * @param commitDate      date of the commit currently being processed
     * @param antipatterns    anti-pattern counts observed for the commit
     * @param metrics         metric values observed for the commit
     */
    private void updateWeeklySummary(Map<LocalDate, WeeklySummary> weeklySummaries, LocalDate commitDate,
                                     Map<String, Integer> antipatterns, Map<String, Double> metrics) {
        LocalDate weekStart = calculateWeekStart(commitDate);
        LocalDate weekEnd = calculateWeekEnd(weekStart);

        WeeklySummary summary = weeklySummaries.computeIfAbsent(weekStart, unused -> new WeeklySummary(
                weekStart,
                weekEnd));

        summary.addCommit(antipatterns, metrics);
    }

    private LocalDate calculateWeekStart(LocalDate commitDate) {
        if (Objects.nonNull(analysisStartDate)) {
            if (commitDate.isBefore(analysisStartDate)) {
                return analysisStartDate;
            }

            long daysFromStart = ChronoUnit.DAYS.between(analysisStartDate, commitDate);
            long weekOffset = Math.floorDiv(daysFromStart, 7);
            return analysisStartDate.plusWeeks(weekOffset);
        }

        return commitDate.with(WEEK_FIELDS.dayOfWeek(), 1);
    }

    private LocalDate calculateWeekEnd(LocalDate weekStart) {
        if (Objects.nonNull(analysisStartDate)) {
            LocalDate candidate = weekStart.plusDays(6);
            if (Objects.nonNull(analysisEndDate) && candidate.isAfter(analysisEndDate)) {
                return analysisEndDate;
            }
            return candidate;
        }

        return weekStart.with(WEEK_FIELDS.dayOfWeek(), 7);
    }

    /**
     * Write a weekly analysis sheet aggregating commit data by week.
     *
     * @param weeklySummaries aggregated weekly metrics
     */
    private void writeWeeklyAnalysis(Map<LocalDate, WeeklySummary> weeklySummaries) {
        if (weeklySummaries.isEmpty()) {
            return;
        }

        XSSFSheet weeklySheet = workbook.createSheet(config.getSystemName() + " Weekly Analysis");
        Row headerRow = weeklySheet.createRow(0);
        headerRow.createCell(0).setCellValue("Week Start");
        headerRow.createCell(1).setCellValue("Week End");
        headerRow.createCell(2).setCellValue("Commit Count");

        for (int i = 0; i < ANTIPATTERNS; i++) {
            headerRow.createCell(i + 3).setCellValue(columnLabels[i + 2]);
        }

        for (int i = 0; i < METRICS; i++) {
            headerRow.createCell(i + 3 + ANTIPATTERNS).setCellValue(columnLabels[i + 2 + ANTIPATTERNS]);
        }

        int rowIndex = 1;
        for (WeeklySummary summary : weeklySummaries.values()) {
            Row row = weeklySheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(summary.getWeekStart().toString());
            row.createCell(1).setCellValue(summary.getWeekEnd().toString());
            row.createCell(2).setCellValue(summary.getCommitCount());

            int cellIndex = 3;
            for (int i = 0; i < ANTIPATTERNS; i++) {
                row.createCell(cellIndex++).setCellValue(summary.getAntiPatternTotal(i));
            }

            for (int i = 0; i < METRICS; i++) {
                row.createCell(cellIndex++).setCellValue(summary.getMetricAverage(i));
            }
        }
    }

    /**
     * Create JSON array from list of architectural rule objects
     *
     * @param archRulesList list of AR objects
     * @return JSON array with list entities
     */
    public static JsonArray toJsonArray(List<List<AbstractAR>> archRulesList) {
        JsonArray outerArray = new JsonArray();

        for (List<AbstractAR> archRules : archRulesList) {
            JsonArray innerArray = new JsonArray();
            for (AbstractAR archRule : archRules) {
                innerArray.add(archRule.toJsonObject());
            }
            outerArray.add(innerArray);
        }

        return outerArray;
    }

    /**
     * Ensure MicroserviceSystem instances loaded from disk are safe to use by
     * replacing null collections and pruning stray null entries.
     *
     * @param system the MicroserviceSystem to sanitize
     * @return the sanitized MicroserviceSystem instance
     */
    private MicroserviceSystem sanitizeMicroserviceSystem(MicroserviceSystem system) {
        if (system == null) {
            return new MicroserviceSystem(config.getSystemName(), "", new HashSet<>(), new HashSet<>());
        }

        if (system.getMicroservices() == null) {
            system.setMicroservices(new HashSet<>());
        } else {
            system.getMicroservices().removeIf(Objects::isNull);
        }

        if (system.getOrphans() == null) {
            system.setOrphans(new HashSet<>());
        } else {
            system.getOrphans().removeIf(Objects::isNull);
        }

        return system;
    }

    /**
     * Container for aggregating weekly values before they are written to the workbook.
     */
    private static class WeeklySummary {
        private final LocalDate weekStart;
        private final LocalDate weekEnd;
        private int commitCount;
        private final double[] antiPatternTotals = new double[ANTIPATTERNS];
        private final double[] metricTotals = new double[METRICS];

        WeeklySummary(LocalDate weekStart, LocalDate weekEnd) {
            this.weekStart = weekStart;
            this.weekEnd = weekEnd;
        }

        void addCommit(Map<String, Integer> antipatterns, Map<String, Double> metrics) {
            commitCount++;

            for (int i = 0; i < ANTIPATTERNS; i++) {
                String label = columnLabels[i + 2];
                double value = antipatterns.getOrDefault(label, "No API Gateway".equals(label) ? 1 : 0);
                antiPatternTotals[i] += value;
            }

            for (int i = 0; i < METRICS; i++) {
                String label = columnLabels[i + 2 + ANTIPATTERNS];
                double value = metrics.getOrDefault(label, 0.0);
                metricTotals[i] += value;
            }
        }

        LocalDate getWeekStart() {
            return weekStart;
        }

        LocalDate getWeekEnd() {
            return weekEnd;
        }

        int getCommitCount() {
            return commitCount;
        }

        double getAntiPatternTotal(int index) {
            return antiPatternTotals[index];
        }

        double getMetricAverage(int index) {
            if (commitCount == 0) {
                return 0.0;
            }
            return metricTotals[index] / commitCount;
        }
    }
}
