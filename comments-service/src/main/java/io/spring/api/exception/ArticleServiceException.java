package io.spring.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ArticleServiceException extends RuntimeException {
  public ArticleServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
