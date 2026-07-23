package tech.wenisch.contextcrate.queue;

import java.util.Arrays;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;

@Configuration
@ConditionalOnProperty(name = "contextcrate.queue.backend", havingValue = "rabbitmq")
public class RabbitQueueConfiguration {
  public static final String EXCHANGE = "contextcrate.pipeline";

  @Bean
  Declarables contextcrateQueues() {
    var exchange = ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    var declarations = new java.util.ArrayList<Declarable>();
    declarations.add(exchange);
    Arrays.stream(WorkStage.values())
        .forEach(
            stage -> {
              var name = "contextcrate." + stage.name().toLowerCase();
              var dlq = QueueBuilder.durable(name + ".dlq").build();
              var queue =
                  QueueBuilder.durable(name)
                      .deadLetterExchange(EXCHANGE)
                      .deadLetterRoutingKey(stage.name().toLowerCase() + ".dlq")
                      .build();
              declarations.add(queue);
              declarations.add(dlq);
              declarations.add(
                  BindingBuilder.bind(queue)
                      .to(exchange)
                      .with(stage.name().toLowerCase())
                      .noargs());
              declarations.add(
                  BindingBuilder.bind(dlq)
                      .to(exchange)
                      .with(stage.name().toLowerCase() + ".dlq")
                      .noargs());
            });
    return new Declarables(declarations);
  }
}
