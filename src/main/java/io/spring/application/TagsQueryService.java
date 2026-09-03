package io.spring.application;

import io.spring.application.tag.TagQueryPort;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class TagsQueryService {
  private TagQueryPort tagQueryPort;

  public List<String> allTags() {
    return tagQueryPort.allTags();
  }
}
