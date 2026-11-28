package io.spring.api.exception;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Valid;
import javax.validation.Validation;
import javax.validation.constraints.NotBlank;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.context.request.ServletWebRequest;

public class CustomizeExceptionHandlerTest {
  private final CustomizeExceptionHandler handler = new CustomizeExceptionHandler();
  private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

  static class Param {
    @NotBlank(message = "can't be empty")
    String title = "";
  }

  static class Wrapper {
    @Valid Param param = new Param();
  }

  static class Root {
    @Valid Wrapper wrapper = new Wrapper();
  }

  @Test
  public void should_map_invalid_request_to_unprocessable_entity_with_field_errors() {
    MapBindingResult errors = new MapBindingResult(new HashMap<>(), "article");
    errors.rejectValue("title", "NotBlank", "can't be empty");

    ResponseEntity<Object> response =
        handler.handleInvalidRequest(new InvalidRequestException(errors), request);

    Assertions.assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    Assertions.assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    ErrorResource body = (ErrorResource) response.getBody();
    Assertions.assertEquals(1, body.getFieldErrors().size());
    FieldErrorResource fieldError = body.getFieldErrors().get(0);
    Assertions.assertEquals("article", fieldError.getResource());
    Assertions.assertEquals("title", fieldError.getField());
    Assertions.assertEquals("NotBlank", fieldError.getCode());
    Assertions.assertEquals("can't be empty", fieldError.getMessage());
  }

  @Test
  public void should_map_invalid_authentication_to_message_body() {
    ResponseEntity<Object> response =
        handler.handleInvalidAuthentication(new InvalidAuthenticationException(), request);

    Assertions.assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    Assertions.assertEquals(
        "invalid email or password", ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  public void should_map_constraint_violations_to_field_errors() {
    Set<ConstraintViolation<Root>> violations =
        Validation.buildDefaultValidatorFactory().getValidator().validate(new Root());

    ErrorResource resource =
        handler.handleConstraintViolation(new ConstraintViolationException(violations), request);

    Assertions.assertEquals(1, resource.getFieldErrors().size());
    FieldErrorResource fieldError = resource.getFieldErrors().get(0);
    Assertions.assertEquals(Root.class.getName(), fieldError.getResource());
    Assertions.assertEquals("title", fieldError.getField());
    Assertions.assertEquals("NotBlank", fieldError.getCode());
    Assertions.assertEquals("can't be empty", fieldError.getMessage());
  }
}
