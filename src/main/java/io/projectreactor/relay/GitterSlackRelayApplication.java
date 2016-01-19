package io.projectreactor.relay;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.nio.NioEventLoopGroup;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Processors;
import reactor.core.support.NamedDaemonThreadFactory;
import reactor.io.buffer.Buffer;
import reactor.io.codec.json.JsonCodec;
import reactor.io.net.http.model.Headers;
import reactor.io.net.impl.netty.NettyClientSocketOptions;
import reactor.rx.Stream;
import reactor.rx.net.http.ReactorHttpHandler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import static com.jayway.jsonpath.JsonPath.read;
import static reactor.rx.net.NetStreams.httpClient;

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
		return new NioEventLoopGroup(Processors.DEFAULT_POOL_SIZE, new NamedDaemonThreadFactory("gitter-slack-relay"));
	}

	/**
	 * Reactor {@link reactor.io.net.config.ClientSocketOptions} that pass the {@code sharedEventLoopGroup} to Netty.
	 *
	 * @return
	 */
	@Bean
	public NettyClientSocketOptions clientSocketOptions() {
		return new NettyClientSocketOptions().eventLoopGroup(sharedEventLoopGroup());
	}

	/**
	 * Handler for setting the Authorization and Accept headers and leaves the connection open by returning {@link
	 * reactor.rx.Streams#never()}.
	 *
	 * @return
	 */
	@Bean
	public ReactorHttpHandler<Buffer, Buffer> gitterStreamHandler() {
		return ch -> {
			ch.header("Authorization", "Bearer " + gitterToken)
					.header("Accept", "application/json");
			return Stream.never();
		};
	}

	/**
	 * creates an HTTP client to connect to Gitter's streaming API.
	 *
	 * @return
	 */
	public Mono<Void> gitterSlackRelay() {
		return httpClient()
				.get(gitterStreamUrl, gitterStreamHandler())
				.flatMap(replies -> replies
						.filter(b -> b.remaining() > 2) // ignore gitter keep-alives (\r)
						.map(new JsonCodec<>(Map.class).decoder()) // ObjectMapper.readValue(Map.class)
						.window(10, 1, TimeUnit.SECONDS) // microbatch 10 items or 1s worth into individual streams (for reduce ops)
						.flatMap(w -> postToSlack(
										w.map(m -> formatLink(m) + ": " + formatText(m))
										.reduce("", GitterSlackRelayApplication::appendLines))
								)
				)
				.after(); // only complete when all windows have completed AND gitter GET connection has closed
	}

	private Mono<Void> postToSlack(Mono<String> input) {
		return httpClient(spec -> spec.options(clientSocketOptions()))
				.post(slackWebhookUrl, out ->
								out.header(Headers.CONTENT_TYPE, "application/json")
										.writeWith(input.map(s -> Buffer.wrap("{\"text\": \"" + s + "\"}")))
						//will close after write has flushed the batched window
				)
				.then(Stream::after); //promote completion to returned promise when last reply has been consumed
		// (usually 1 from slack response packet)
	}

	public static void main(String[] args) throws Throwable {
		ApplicationContext ctx = SpringApplication.run(GitterSlackRelayApplication.class, args);
		GitterSlackRelayApplication app = ctx.getBean(GitterSlackRelayApplication.class);

		Stream<Void> clientState = Stream
				.defer(app::gitterSlackRelay)
				.log("gitter-client-state")
				.repeat() //keep alive if get client closes
				.retry(); //keep alive if any error

		Stream.await(clientState);
	}

	private static String formatDate(Object o) {
		DateTimeFormatter isoDateFmt = ISODateTimeFormat.dateTime();
		DateTimeFormatter shortFmt = DateTimeFormat.forPattern("d-MMM H:mm:ss");
		DateTime dte = isoDateFmt.parseDateTime((String) o);
		return shortFmt.print(dte);
	}

	private static String formatLink(Map m) {
		return "<https://gitter.im/reactor/reactor?at=" +
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
