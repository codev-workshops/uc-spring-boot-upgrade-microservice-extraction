package io.spring.infrastructure.extraction.tag;

/** Transport or non-2xx failure while calling article-service. */
public class ArticleServiceException extends RuntimeException {
  public ArticleServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
