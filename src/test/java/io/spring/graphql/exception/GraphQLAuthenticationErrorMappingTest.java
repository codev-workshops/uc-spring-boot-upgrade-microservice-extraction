package io.spring.graphql.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.netflix.graphql.types.errors.ErrorType;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.spring.api.exception.InvalidAuthenticationException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

public class GraphQLAuthenticationErrorMappingTest {

  private final GraphQLCustomizeExceptionHandler handler = new GraphQLCustomizeExceptionHandler();

  private DataFetcherExceptionHandlerResult handle(Throwable exception) {
    DataFetchingEnvironment env =
        DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .executionStepInfo(
                ExecutionStepInfo.newExecutionStepInfo()
                    .type(Scalars.GraphQLString)
                    .path(ResultPath.parse("/feed"))
                    .build())
            .build();
    DataFetcherExceptionHandlerParameters parameters =
        DataFetcherExceptionHandlerParameters.newExceptionParameters()
            .exception(exception)
            .dataFetchingEnvironment(env)
            .build();
    return handler.onException(parameters);
  }

  @Test
  public void should_map_authentication_exception_to_unauthenticated() {
    List<GraphQLError> errors = handle(new AuthenticationException()).getErrors();

    assertEquals(1, errors.size());
    GraphQLError error = errors.get(0);
    assertEquals(
        ErrorType.UNAUTHENTICATED.name(), String.valueOf(error.getExtensions().get("errorType")));
    assertEquals("Authentication required", error.getMessage());
    assertEquals(Collections.singletonList("feed"), error.getPath());
  }

  @Test
  public void should_still_map_invalid_authentication_exception_to_unauthenticated() {
    List<GraphQLError> errors = handle(new InvalidAuthenticationException()).getErrors();

    assertEquals(1, errors.size());
    GraphQLError error = errors.get(0);
    assertEquals(
        ErrorType.UNAUTHENTICATED.name(), String.valueOf(error.getExtensions().get("errorType")));
    assertEquals("invalid email or password", error.getMessage());
    assertEquals(Collections.singletonList("feed"), error.getPath());
  }
}
