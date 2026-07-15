package io.spring.api.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/profiles")
@AllArgsConstructor
public class InternalProfileApi {
  private UserReadService userReadService;
  private UserRelationshipQueryService userRelationshipQueryService;
  private ObjectMapper objectMapper;

  @PostMapping("/batch")
  public List<ProfileResponse> findProfiles(HttpServletRequest httpRequest) throws IOException {
    BatchProfileRequest request =
        objectMapper
            .readerFor(BatchProfileRequest.class)
            .without(DeserializationFeature.UNWRAP_ROOT_VALUE)
            .readValue(httpRequest.getInputStream());
    if (request.getUserIds() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userIds is required");
    }
    List<String> userIds = new ArrayList<>(new LinkedHashSet<>(request.getUserIds()));
    if (userIds.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> following =
        request.getViewerId() == null
            ? Collections.emptySet()
            : userRelationshipQueryService.followingAuthors(request.getViewerId(), userIds);
    return userReadService.findByIds(userIds).stream()
        .map(
            user ->
                new ProfileResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getBio(),
                    user.getImage(),
                    following.contains(user.getId())))
        .collect(Collectors.toList());
  }
}

@Data
@NoArgsConstructor
class BatchProfileRequest {
  private String viewerId;

  private List<String> userIds;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class ProfileResponse {
  private String id;
  private String username;
  private String bio;
  private String image;
  private boolean following;
}
