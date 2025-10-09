# Change Impact Microservice Evolution Tool 2

This project tracks microservice system evolution changes across repositories.


## Prerequisites

* Maven 3.6+
* Java 11+ (11 Recommended)  

## To Compile:
    ``mvn clean install -DskipTests``

## Extracting an Intermediate Representation:
- Run or compile the main method of ``IRExtractionRunner.java`` in the IDE of your choice or via the command line.
- Command line args list containing ``/path/to/config/<Config-File>.json``

Sample input config file:

```json
{
  "systemName": "Train-ticket",
  "repositoryURL": "https://github.com/g-goulis/train-ticket-microservices-test.git",
  "endCommit": "06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7",
  "branch": "main",
  "baseBranch": "main"
}
```

Sample output produced:
```json
{
  "name": "Train-ticket",
  "commitID": "1.0",
  "microservices": [
    {
      "name": "ts-rebook-service",
      "path": ".\\clone\\train-ticket-microservices-test\\ts-rebook-service",
      "controllers": [
        {
          "packageName": "com.cloudhubs.trainticket.rebook.controller",
          "name": "WaitListOrderController.java",
          "path": ".\\clone\\train-ticket-microservices-test\\ts-rebook-service\\src\\main\\java\\com\\cloudhubs\\trainticket\\rebook\\controller\\WaitListOrderController.java",
          "classRole": "CONTROLLER",
          "annotations": [
            {
              "name": "RequestMapping",
              "contents": "\"/api/v1/waitorderservice\""
            },
            ...
          ],
          "fields": [
            {
              "name": "waitListOrderService",
              "type": "WaitListOrderService"
            },
            ...
          ],
          "methods": [
            {
              "name": "getAllOrders",
              "annotations": [
                {
                  "name": "GetMapping",
                  "contents": "[path \u003d \"/orders\"]"
                }
              ],
              "parameters": [
                {
                  "name": "HttpHeaders",
                  "type": "headers"
                }
              ],
              "returnType": "HttpEntity",
              "url": "/api/v1/waitorderservice/orders",
              "httpMethod": "GET",
              "microserviceName": "ts-rebook-service"
            },
            ...
          ],
          "methodCalls": [
            {
              "name": "info",
              "objectName": "LOGGER",
              "calledFrom": "getWaitListOrders",
              "parameterContents": "\"[getWaitListOrders][Get All Wait List Orders]\""
            },
            ...
          ]
        },
        ...
      ],
      "Services": [...],
      "Repositories": [...],
      "Entities": [...],
    ],
    "orphans": [...]
}
```

## Extracting a Delta Change Impact:
- Run or compile the main method of ``DeltaExtractionRunner.java`` in the IDE of your choice or via the command line.
- Command line args list containing ``/path/to/config/<Config-File>.json  <oldCommit>  <newCommit>``

Sample output produced:
```json
{
  "oldCommit": "06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7",
  "newCommit": "82949fa07dcf82f66641f5807d629d15bab663a6",
  "changes": [
    {
      "oldPath": ".\\clone\\train-ticket-microservices-test\\ts-price-service\\src\\main\\java\\com\\cloudhubs\\trainticket\\price\\controller\\PriceController.java",
      "newPath": ".\\clone\\train-ticket-microservices-test\\ts-price-service\\src\\main\\java\\com\\cloudhubs\\trainticket\\price\\controller\\PriceController.java",
      "changeType": "MODIFY",
      "classChange": {}
    },
    ...
  ]
}
```

## Merging an IR & System Change:
- Run or compile the main method of ``IRMergeRunner.java`` in the IDE of your choice or via the command line.
- Provide command line args containing ``path/to/IR/<IR-File>.json  path/to/Delta/<IR-File>.json  /path/to/config/<Config-File>.json``

## Running ISAR metrics
- Execute the ISAR metrics runner with Maven using the dedicated profile:
  ``mvn -P run-metrics exec:java -Dexec.args="/path/to/config/<Config-File>.json  <oldCommit>  <newCommit>" -DskipTests``

  * Provide one or more commit SHAs after the config path to calculate metrics for specific revisions.
  * If two commits are supplied, the tool generates a metrics report for each commit in order (for example, two commits representing a two-month window).
  * Omitting commit IDs defaults to analyzing the repository head.

  The generated report is written to ``output/metrics-summary.txt`` and streamed to the console.

## GitHub Actions support

### Spinnaker analysis workflow

This repository now provides a reusable GitHub Actions workflow for running the IR extraction against the [spinnaker/spinnaker](https://github.com/spinnaker/spinnaker) project. The workflow lives at `.github/workflows/spinnaker-analysis.yml` and can be triggered manually from the **Actions** tab by selecting **Spinnaker Cimet Analysis** and clicking **Run workflow**.

The workflow performs the following steps:

1. Builds the Cimet project with Maven.
2. Generates a temporary `config.json` targeting the Spinnaker repository (default branch `main`, configurable via workflow dispatch input).
3. Executes the IR extraction runner (`mvn -P run-ir exec:java`).
4. Uploads the generated `output/IR.json` as an artifact for download and further analysis.

If you need to analyze a different branch or tag of Spinnaker, provide the desired ref in the workflow dispatch form before starting the run.

### Spinnaker delta analysis workflow

The `.github/workflows/spinnaker-delta-analysis.yml` workflow builds on top of the IR extraction workflow and focuses on generating an IR delta for changes committed to [spinnaker/spinnaker](https://github.com/spinnaker/spinnaker) within the last two months. Trigger **Spinnaker Cimet Delta Analysis** from the **Actions** tab to run it.

During execution the workflow:

1. Builds the Cimet project and prepares a `config.json` targeting Spinnaker.
2. Clones the requested branch (default `main`) of Spinnaker and determines the most recent commit and the latest commit at or before the two-month cut-off.
3. Runs the delta extraction runner (`mvn -P run-delta exec:java`) between the two commits.
4. Uploads the generated `output/Delta.json` artifact so you can inspect the detected changes.

Supplying a different branch or tag in the workflow dispatch form adjusts both commits before running the delta extraction.

### Spinnaker ISAR metrics workflow

Use the `.github/workflows/spinnaker-metric-extraction.yml` workflow to run CIMET's ISAR metrics module against [spinnaker/spinnaker](https://github.com/spinnaker/spinnaker). Launch **Run metric extraction (CIMET's ISAR metrics module)** from the **Actions** tab and provide an optional branch or tag (default `main`).

The workflow performs the following steps:

1. Builds the CIMET project with Maven.
2. Clones the selected branch of Spinnaker and identifies the most recent commit together with the latest commit at or before the two-month cut-off.
3. Generates a temporary `config.json` targeting the desired branch of Spinnaker.
4. Executes the ISAR metrics runner (`mvn -P run-metrics exec:java`) providing both commits so the report contains metrics snapshots for the entire two-month window.
5. Uploads the generated `output/metrics-summary.txt` artifact so you can review the reported cohesion and coupling metrics for each commit.
