package io.spring.harness;

/**
 * The two sides a request can be routed to during the strangler migration: the monolith
 * implementation that exists today and the extracted microservice that a later phase introduces
 * behind a feature flag.
 */
public enum RoutePath {
  MONOLITH,
  EXTRACTED
}
