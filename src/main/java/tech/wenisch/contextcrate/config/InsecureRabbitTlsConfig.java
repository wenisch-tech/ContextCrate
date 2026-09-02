package tech.wenisch.contextcrate.config;

import org.springframework.boot.amqp.autoconfigure.ConnectionFactoryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.wenisch.contextcrate.util.InsecureSsl;

/**
 * Skips TLS certificate and hostname validation for the RabbitMQ (AMQPS) connection when both the
 * global trust-all flag and TLS itself are enabled. A plain, non-TLS AMQP setup is never silently
 * upgraded to TLS by this class.
 */
@Configuration
@ConditionalOnProperty(
    name = {"contextcrate.tls.trust-all-certificates", "spring.rabbitmq.ssl.enabled"},
    havingValue = "true")
public class InsecureRabbitTlsConfig {

  @Bean
  ConnectionFactoryCustomizer insecureRabbitConnectionFactoryCustomizer() {
    return factory -> {
      try {
        // ConnectionFactory does not verify hostnames unless enableHostnameVerification() is
        // called, so leaving it untouched keeps hostname checks off alongside the trust-all
        // certificate chain below.
        factory.useSslProtocol(InsecureSsl.trustAllContext());
      } catch (Exception e) {
        throw new IllegalStateException("Failed to relax TLS validation for the RabbitMQ connection", e);
      }
    };
  }
}
