package io.spring.article.api.exception;

/** Rendered as 404 {"errors":{"body":["message"]}}. */
@SuppressWarnings("serial")
public class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException() {
    super("article not found");
  }
}
