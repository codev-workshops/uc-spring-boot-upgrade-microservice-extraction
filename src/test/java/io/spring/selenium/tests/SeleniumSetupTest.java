package io.spring.selenium.tests;

import static org.testng.Assert.*;

import org.testng.annotations.Test;

/** Simple test to verify Selenium setup is working correctly. */
public class SeleniumSetupTest extends BaseTest {

  @Test(groups = {"smoke"})
  public void testBrowserLaunches() {
    createTest("testBrowserLaunches", "Verify browser launches and can navigate");

    // Navigate to a simple page
    driver.get("https://www.google.com");

    // Verify we got a page title
    String title = driver.getTitle();
    assertNotNull(title, "Page title should not be null");
    assertTrue(title.length() > 0, "Page title should not be empty");

    test.info("Successfully navigated to Google. Title: " + title);
  }

  @Test(groups = {"smoke"})
  public void testWebDriverManagerSetup() {
    createTest("testWebDriverManagerSetup", "Verify WebDriverManager configured the driver");

    // If we get here, WebDriverManager successfully set up the driver
    assertNotNull(driver, "WebDriver should be initialized");

    test.info("WebDriver successfully initialized: " + driver.getClass().getSimpleName());
  }
}
