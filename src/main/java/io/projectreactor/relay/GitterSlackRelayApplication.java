package io.projectreactor.relay;

import static com.jayway.jsonpath.JsonPath.read;
import static reactor.ipc.netty.http.client.HttpClient.create;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.http.client.HttpClientOptions;
import reactor.ipc.netty.http.client.HttpClientRequest;

/**
 * A Spring Boot application that relays messages from a Gitter chat room to a Slack webhook to aggregate content into
 * a Slack channel.
 */
@SpringBootApplication
public class GitterSlackRelayApplication {
	/**
	 * Token used in the Authorization header sent to Gitter's streaming API.
	 */
	@Value("${gitter.token}")
	private String gitterToken;
	/**
	 * URL to connect to to stream Gitter messages from a chat room.
	 */
	@Value("https://stream.gitter.im/v1/rooms/${gitter.roomId}/chatMessages")
	private String gitterStreamUrl;
	/**
	 * URL to POST formatted messages to to appear in a Slack channel.
	 */
	@Value("${slack.webhookUrl}")
	private String slackWebhookUrl;

	/**
	 * Whether to shut this service down or not.
	 *
	 * @return
	 */
	@Bean
	public AtomicBoolean shutdownFlag() {
		return new AtomicBoolean(false);
	}

	/**
	 * A shared NioEventLoopGroup for reusing resources when creating new HTTP clients.
	 *
	 * @return
	 */
	@Bean
	public NioEventLoopGroup sharedEventLoopGroup() {
		return new NioEventLoopGroup(Schedulers.DEFAULT_POOL_SIZE,
				(Runnable r) -> new Thread(r, "gitter-slack-relay"));
	}

	/**
	 * Reactor {@link reactor.ipc.netty.config.ClientOptions} that pass the {@code sharedEventLoopGroup} to Netty.
	 *
	 * @return
	 */
	@Bean
	public Consumer<HttpClientOptions> clientSocketOptions() {
		return consumer -> HttpClientOptions.create().eventLoopGroup(sharedEventLoopGroup());
	}

	/**
	 * Handler for setting the Authorization and Accept headers and leaves the connection open by returning {@link
	 * Flux#never()}.
	 *
	 * @return
	 */
	@Bean
	public Function<HttpClientRequest, Mono<Void>> gitterStreamHandler() {
		return ch -> {
			ch.header("Authorization", "Bearer " + gitterToken)
					.header("Accept", "application/json");
			return Mono.never();
		};
	}

	/**
	 * creates an HTTP client to connect to Gitter's streaming API.
	 *
	 * @return
	 */
	public Mono<Void> gitterSlackRelay() {
		ObjectMapper mapper = new ObjectMapper();
		return create()
				.get(gitterStreamUrl, gitterStreamHandler())
				.flatMapMany(replies -> replies
						.receive()
						.asByteArray()
						.filter(b -> b.length > 2) // ignore gitter keep-alives (\r)
						.map(b -> {
							try {
								return mapper.readValue(b, Map.class);
							}
							catch (IOException e) {
								throw Exceptions.propagate(e);
							}
						}) // ObjectMapper.readValue(Map
						// .class)
						.window(10, 1_000) // microbatch 10 items or 1s worth into individual streams (for reduce ops)
						.flatMap(w -> postToSlack(
										w.map(m -> formatLink(m) + ": " + formatText(m))
										.reduce("", GitterSlackRelayApplication::appendLines))
								)
				)
				.then(); // only complete when all windows have completed AND gitter GET connection has closed
	}

	private Mono<Void> postToSlack(Mono<String> input) {
		return create(clientSocketOptions())
				.post(slackWebhookUrl, out ->
								out.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
								   .sendString(input.map(s -> "{\"text\": \"" + s + "\"}"))
						//will close after write has flushed the batched window
				)
				.then(r -> r.receive().then()); //promote completion to returned promise when last reply has been
		// consumed
		// (usually 1 from slack response packet)
	}

	public static void main(String[] args) throws Throwable {
		ApplicationContext ctx = SpringApplication.run(GitterSlackRelayApplication.class, args);
		GitterSlackRelayApplication app = ctx.getBean(GitterSlackRelayApplication.class);

		Flux
				.defer(app::gitterSlackRelay)
				.log("gitter-client-state")
				.repeat() //keep alive if get client closes
				.retry() //keep alive if any error
				.subscribe();

		CountDownLatch latch = new CountDownLatch(1);
		latch.await();

	}

	private static String formatDate(Object o) {
		DateTimeFormatter isoDateFmt = ISODateTimeFormat.dateTime();
		DateTimeFormatter shortFmt = DateTimeFormat.forPattern("d-MMM H:mm:ss");
		DateTime dte = isoDateFmt.parseDateTime((String) o);
		return shortFmt.print(dte);
	}

	private static String formatLink(Map m) {
		return "<https://gitter.im/BroadleafCommerce/BroadleafCommerce?at=" +
				read(m, "$.id") + "|" + read(m, "$.fromUser.displayName") +
				" [" + formatDate(read(m, "$.sent")) + "]>";
	}

	private static String formatText(Map m) {
		return ((String) read(m, "$.text")).replaceAll("\n", "\\\\n");
	}

	private static String appendLines(String prev, String next) {
		return (!"".equals(prev) ? prev + "\\\\n" + next : next);
	}

}
