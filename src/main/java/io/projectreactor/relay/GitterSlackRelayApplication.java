package io.projectreactor.relay;

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
				.get("https://stream.gitter.im/v1/rooms/" + gitterRoomId + "/chatMessages", gitterHandler())
				.flatMap(p -> p
						.filter(b -> b.remaining() > 2)
						.decode(new JsonCodec<>(Map.class))
						.window(10, 1, TimeUnit.SECONDS)
						.flatMap(msgs -> msgs.map(msg -> msg.get("text"))
						                     .reduce("", (prev, next) -> prev + "\n" + next))
						.observe(batch -> {
							System.out.println("batch: " + batch);
						})
						.after());
	}

	public static void main(String[] args) throws InterruptedException {
		ConfigurableApplicationContext ctx = SpringApplication.run(GitterSlackRelayApplication.class, args);

		ctx.getBean(Promise.class)
		   .await(-1, TimeUnit.SECONDS);
	}

}
