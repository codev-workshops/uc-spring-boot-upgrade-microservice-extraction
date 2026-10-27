package io.spring.articles.infrastructure.client.dto;

public record FavoriteDto(String articleId, int favoritesCount, boolean favorited) {}
