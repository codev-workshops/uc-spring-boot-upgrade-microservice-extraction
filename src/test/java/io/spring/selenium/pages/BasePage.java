package io.spring.selenium.pages;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/** Base page class providing common functionality for all page objects. */
public abstract class BasePage {

  protected WebDriver driver;
  protected WebDriverWait wait;
  protected static final long DEFAULT_TIMEOUT_SECONDS = 10;

  public BasePage(WebDriver driver) {
    this.driver = driver;
    this.wait = new WebDriverWait(driver, DEFAULT_TIMEOUT_SECONDS);
    PageFactory.initElements(driver, this);
  }

  /** Wait for element to be visible and return it. */
  protected WebElement waitForVisibility(WebElement element) {
    return wait.until(ExpectedConditions.visibilityOf(element));
  }

  /** Wait for element to be clickable and return it. */
  protected WebElement waitForClickable(WebElement element) {
    return wait.until(ExpectedConditions.elementToBeClickable(element));
  }

  /** Click an element after waiting for it to be clickable. */
  protected void click(WebElement element) {
    waitForClickable(element).click();
  }

  /** Type text into an element after clearing it. */
  protected void type(WebElement element, String text) {
    WebElement visibleElement = waitForVisibility(element);
    visibleElement.clear();
    visibleElement.sendKeys(text);
  }

  /** Get text from an element after waiting for visibility. */
  protected String getText(WebElement element) {
    return waitForVisibility(element).getText();
  }

  /** Check if element is displayed. */
  protected boolean isDisplayed(WebElement element) {
    try {
      return element.isDisplayed();
    } catch (Exception e) {
      return false;
    }
  }
}
