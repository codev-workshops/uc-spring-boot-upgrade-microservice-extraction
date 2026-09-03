package io.spring.selenium.listeners;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

/** TestNG listener for generating failure reports. */
public class TestListener implements ITestListener {

  private static final String REPORT_DIR = "build/reports/selenium/";

  private int totalTests = 0;
  private int passedTests = 0;
  private int failedTests = 0;
  private int skippedTests = 0;
  private StringBuilder failureDetails = new StringBuilder();

  @Override
  public void onTestStart(ITestResult result) {
    totalTests++;
  }

  @Override
  public void onTestSuccess(ITestResult result) {
    passedTests++;
  }

  @Override
  public void onTestFailure(ITestResult result) {
    failedTests++;
    appendFailureDetails(result);
  }

  @Override
  public void onTestSkipped(ITestResult result) {
    skippedTests++;
  }

  @Override
  public void onFinish(ITestContext context) {
    if (failedTests > 0) {
      generateFailureReport();
    }
  }

  private void appendFailureDetails(ITestResult result) {
    failureDetails.append("\n### ").append(result.getName()).append("\n");
    failureDetails.append("- **Class:** ").append(result.getTestClass().getName()).append("\n");
    failureDetails.append("- **Method:** ").append(result.getMethod().getMethodName()).append("\n");
    failureDetails
        .append("- **Failure Reason:** ")
        .append(result.getThrowable().getMessage())
        .append("\n");
    failureDetails.append("- **Stack Trace:**\n```\n");

    for (StackTraceElement element : result.getThrowable().getStackTrace()) {
      failureDetails.append(element.toString()).append("\n");
    }
    failureDetails.append("```\n");
  }

  private void generateFailureReport() {
    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    String reportPath = REPORT_DIR + "failure-report-" + timestamp + ".md";

    try {
      Files.createDirectories(Paths.get(REPORT_DIR));

      try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath))) {
        writer.println("# Test Failure Report - " + timestamp);
        writer.println();
        writer.println("## Summary");
        writer.println("- Total Tests: " + totalTests);
        writer.println("- Passed: " + passedTests);
        writer.println("- Failed: " + failedTests);
        writer.println("- Skipped: " + skippedTests);
        writer.println();
        writer.println("## Failed Tests");
        writer.println(failureDetails.toString());
      }

      // Archive the report
      archiveReport(reportPath, timestamp);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void archiveReport(String reportPath, String timestamp) {
    try {
      Files.createDirectories(Paths.get(REPORT_DIR + "archive"));
      Files.copy(
          Paths.get(reportPath),
          Paths.get(REPORT_DIR + "archive/failure-report-" + timestamp + ".md"));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
