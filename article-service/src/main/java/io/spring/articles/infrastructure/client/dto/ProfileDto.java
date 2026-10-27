package io.spring.articles.infrastructure.client.dto;

public record ProfileDto(String id, String username, String bio, String image, boolean following) {}
