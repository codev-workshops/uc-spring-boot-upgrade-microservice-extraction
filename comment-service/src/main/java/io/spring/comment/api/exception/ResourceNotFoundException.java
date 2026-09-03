package io.spring.comment.api.exception;

/** Rendered as 404 {"errors":{"body":["message"]}}. */
@SuppressWarnings("serial")
public class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException() {
    super("comment not found");
  }
}
