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
import reactor.io.net.http.model.Headers;
import reactor.io.net.impl.netty.NettyClientSocketOptions;
import reactor.rx.Promise;
import reactor.rx.Stream;
import reactor.rx.Streams;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.jayway.jsonpath.JsonPath.read;
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
	public NettyClientSocketOptions clientSocketOptions() {
		return new NettyClientSocketOptions().eventLoopGroup(sharedEventLoopGroup());
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
				.flatMap(ch -> ch
						.filter(b -> b.remaining() > 2)
						.decode(new JsonCodec<>(Map.class))
						.window(10, 1, TimeUnit.SECONDS)
						.log("after-window")
						.flatMap(msg -> httpClient()
								.post(slackWebhookUrl, out -> {
									out.header(Headers.CONTENT_TYPE, "application/json");
									return out.writeWith(msg.map(m -> "*" + read(m, "$.fromUser.displayName") + "* " +
									                                  "_" + read(m, "$.sent") + "_: " +
									                                  replaceNewlines(read(m, "$.text")))
									                        .reduce("", (prev, next) -> (!"".equals(prev) ? prev + "\\\\n" + next : next))
									                        .log("after-reduce")
									                        .map(s -> Buffer.wrap("{\"text\": \"" + s + "\"}")));
								})
								.flatMap(Stream::after)));
	}

	public static void main(String[] args) throws InterruptedException {
		ConfigurableApplicationContext ctx = SpringApplication.run(GitterSlackRelayApplication.class, args);

		ctx.getBean(Promise.class)
		   .await(-1, TimeUnit.SECONDS);
	}

	private static String replaceNewlines(Object o) {
		if (o instanceof String) {
			return ((String) o).replaceAll("\n", "\\\\n");
		} else {
			return replaceNewlines(String.valueOf(o));
		}
	}

}
