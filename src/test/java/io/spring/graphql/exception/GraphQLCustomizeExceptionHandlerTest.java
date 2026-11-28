package io.spring.graphql.exception;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import graphql.GraphQLError;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.ResultPath;
import io.spring.api.exception.InvalidAuthenticationException;
import io.spring.graphql.types.Error;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Valid;
import javax.validation.Validation;
import javax.validation.constraints.NotBlank;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GraphQLCustomizeExceptionHandlerTest {
  private final GraphQLCustomizeExceptionHandler handler = new GraphQLCustomizeExceptionHandler();

  static class Param {
    @NotBlank(message = "can't be empty")
    String email = "";
  }

  static class Inner {
    @Valid Param param = new Param();
  }

  static class Outer {
    @Valid Inner inner = new Inner();
  }

  private static <T> ConstraintViolationException violationsOf(T bean) {
    Set<ConstraintViolation<T>> violations =
        Validation.buildDefaultValidatorFactory().getValidator().validate(bean);
    return new ConstraintViolationException("validation failed", violations);
  }

  private DataFetcherExceptionHandlerParameters params(Throwable exception) {
    DataFetcherExceptionHandlerParameters params =
        mock(DataFetcherExceptionHandlerParameters.class);
    when(params.getException()).thenReturn(exception);
    when(params.getPath()).thenReturn(ResultPath.rootPath().segment("createUser"));
    return params;
  }

  @Test
  public void should_map_invalid_authentication_to_unauthenticated_error() {
    DataFetcherExceptionHandlerResult result =
        handler.onException(params(new InvalidAuthenticationException()));

    Assertions.assertEquals(1, result.getErrors().size());
    GraphQLError error = result.getErrors().get(0);
    Assertions.assertEquals("invalid email or password", error.getMessage());
    Assertions.assertEquals(Collections.singletonList("createUser"), error.getPath());
    Assertions.assertEquals(
        "UNAUTHENTICATED", String.valueOf(error.getExtensions().get("errorType")));
  }

  @Test
  public void should_map_constraint_violation_to_bad_request_with_field_errors() {
    DataFetcherExceptionHandlerResult result =
        handler.onException(params(violationsOf(new Param())));

    Assertions.assertEquals(1, result.getErrors().size());
    GraphQLError error = result.getErrors().get(0);
    Assertions.assertEquals("validation failed", error.getMessage());
    Assertions.assertEquals("BAD_REQUEST", String.valueOf(error.getExtensions().get("errorType")));
    Assertions.assertEquals(
        Collections.singletonList("can't be empty"), error.getExtensions().get("email"));
  }

  @Test
  public void should_strip_first_two_path_segments_of_nested_violation() {
    DataFetcherExceptionHandlerResult result =
        handler.onException(params(violationsOf(new Outer())));

    Assertions.assertEquals(
        Collections.singletonList("can't be empty"),
        result.getErrors().get(0).getExtensions().get("email"));
  }

  @Test
  public void should_delegate_other_exceptions_to_default_handler() {
    DataFetcherExceptionHandlerResult result =
        handler.onException(params(new IllegalStateException("boom")));

    Assertions.assertEquals(1, result.getErrors().size());
    Assertions.assertTrue(result.getErrors().get(0).getMessage().contains("boom"));
  }

  @Test
  public void should_convert_constraint_violations_to_error_data() {
    Error error = GraphQLCustomizeExceptionHandler.getErrorsAsData(violationsOf(new Outer()));

    Assertions.assertEquals("BAD_REQUEST", error.getMessage());
    Assertions.assertEquals(1, error.getErrors().size());
    Assertions.assertEquals("email", error.getErrors().get(0).getKey());
    List<String> messages = error.getErrors().get(0).getValue();
    Assertions.assertEquals(Arrays.asList("can't be empty"), messages);
  }
}
