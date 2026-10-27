package io.spring.articles.infrastructure.client.dto;

import java.util.List;

public record ArticleIdsResponse(List<String> articleIds) {}
