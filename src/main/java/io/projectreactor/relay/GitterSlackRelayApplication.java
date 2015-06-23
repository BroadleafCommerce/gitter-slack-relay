package io.projectreactor.relay;

import com.jayway.jsonpath.JsonPath;
import io.netty.channel.nio.NioEventLoopGroup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import reactor.Environment;
import reactor.core.support.NamedDaemonThreadFactory;
import reactor.io.buffer.Buffer;
import reactor.io.codec.json.JsonCodec;
import reactor.io.net.ReactorChannelHandler;
import reactor.io.net.http.HttpChannel;
import reactor.rx.Promise;
import reactor.rx.Stream;
import reactor.rx.Streams;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static reactor.io.net.NetStreams.httpClient;

@SpringBootApplication
public class GitterSlackRelayApplication {

	static {
		Environment.initializeIfEmpty().assignErrorJournal();
	}

	@Value("${gitter.token}")
	private String gitterToken;
	@Value("${gitter.roomId}")
	private String gitterRoomId;
	@Value("${slack.webhookUrl}")
	private String slackWebhookUrl;

	@Bean
	public NioEventLoopGroup sharedEventLoopGroup() {
		return new NioEventLoopGroup(Environment.PROCESSORS, new NamedDaemonThreadFactory("gitter-slack-relay"));
	}

	@Bean
	public ReactorChannelHandler<Buffer, Buffer, HttpChannel<Buffer, Buffer>> gitterHandler() {
		return ch -> {
			ch.header("Authorization", "Bearer " + gitterToken)
			  .header("Accept", "application/json");
			return Streams.never();
		};
	}

	@Bean
	public Promise<Void> gitterSlackRelay() {
		return httpClient()
				// read from gitter chat
				.get("https://stream.gitter.im/v1/rooms/" + gitterRoomId + "/chatMessages", gitterHandler())
				.flatMap(p -> p
						.filter(b -> b.remaining() > 2) // drop keep-alives which are just newlines
						.decode(new JsonCodec<>(Map.class)) // parse to Map
						.window(10, 1, TimeUnit.SECONDS) // window for 10s
						.flatMap(this::batchMessages) // transform to newline-delimited String
						.observe(batch -> {
							System.out.println("batch: " + batch);
						})
						.after());
	}

	private Stream<String> batchMessages(Stream<Map> in) {
		return in
				.map(msg -> {
					String user = JsonPath.read(msg, "$.fromUser.displayName");
					String text = JsonPath.read(msg, "$.text");
					String sent = JsonPath.read(msg, "$.sent");
					return user + " " + sent + ": " + text;
				})
				.reduce("", (prev, next) -> prev + "\n" + next);
	}

	public static void main(String[] args) throws InterruptedException {
		ConfigurableApplicationContext ctx = SpringApplication.run(GitterSlackRelayApplication.class, args);

		ctx.getBean(Promise.class)
		   .await(-1, TimeUnit.SECONDS);
	}

}
