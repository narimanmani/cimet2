package edu.university.ecs.lab.detection;

import edu.university.ecs.lab.common.error.Error;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

/**
 * Runner class to execute detection service
 */
public class ExcelOutputRunner {

    public static void main(String[] args) throws IOException {
        String configPath = "./config.json";
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            configPath = args[0];
        }
        try {
            File conifgFile = new File(configPath);
            if (!conifgFile.exists()) {
                throw new FileNotFoundException();
            }
        } catch (Exception e) {
            Error.reportAndExit(Error.MISSING_CONFIG, Optional.of(e));
        }
        DetectionService detectionService = new DetectionService(configPath);
        detectionService.runDetection();
    }

}
