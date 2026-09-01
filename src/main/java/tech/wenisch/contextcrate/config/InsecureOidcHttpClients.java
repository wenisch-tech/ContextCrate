package tech.wenisch.contextcrate.config;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;

/** HTTP clients that skip TLS certificate validation, wired only when explicitly opted in. */
public record InsecureOidcHttpClients(RestOperations restOperations, RestClient restClient) {}
