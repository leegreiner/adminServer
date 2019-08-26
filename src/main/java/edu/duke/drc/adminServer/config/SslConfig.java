package edu.duke.drc.adminServer.config;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.web.client.HttpHeadersProvider;
import de.codecentric.boot.admin.server.web.client.InstanceExchangeFilterFunction;
import de.codecentric.boot.admin.server.web.client.InstanceWebClient;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(AdminServerProperties.class)
public class SslConfig {
  private transient final AdminServerProperties adminServerProperties;

  public SslConfig(AdminServerProperties adminServerProperties) {
    this.adminServerProperties = adminServerProperties;
  }
  
  @Bean
  public InstanceWebClient instanceWebClient(HttpHeadersProvider httpHeadersProvider,
      ObjectProvider<List<InstanceExchangeFilterFunction>> filtersProvider) throws SSLException {
    final List<InstanceExchangeFilterFunction> additionalFilters = filtersProvider.getIfAvailable(Collections::emptyList);
    
    return InstanceWebClient.builder()
      .defaultRetries(adminServerProperties.getMonitor().getDefaultRetries())
      .retries(adminServerProperties.getMonitor().getRetries())
      .httpHeadersProvider(httpHeadersProvider)
      .webClient(getWebClient())
      .filters(filters -> filters.addAll(additionalFilters))
      .build();
  }

  private WebClient getWebClient() throws SSLException {
    //TODO need to figure out how to not use InsecureTrustManagerFactory and disable host name verification
    final SslContext sslContext = SslContextBuilder
      .forClient()
      .trustManager(InsecureTrustManagerFactory.INSTANCE)
      .build();

    final HttpClient httpClient = HttpClient.create()
      .compress(true)
      .secure(t -> t.sslContext(sslContext))
      .tcpConfiguration(tcp -> tcp.bootstrap(bootstrap -> bootstrap.option(
              ChannelOption.CONNECT_TIMEOUT_MILLIS,
              (int) adminServerProperties.getMonitor().getConnectTimeout().toMillis()
      )).observe((connection, newState) -> {
          if (ConnectionObserver.State.CONNECTED.equals(newState)) {
              connection.addHandlerLast(new ReadTimeoutHandler(adminServerProperties.getMonitor().getReadTimeout().toMillis(),
                      TimeUnit.MILLISECONDS
              ));
          }
      }));
    
    final ReactorClientHttpConnector reactorClientHttpConnector = new ReactorClientHttpConnector(httpClient);

    return WebClient.builder().clientConnector(reactorClientHttpConnector).build();
  }
}

