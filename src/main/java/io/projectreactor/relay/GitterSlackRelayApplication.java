package io.projectreactor.relay;

import io.netty.channel.nio.NioEventLoopGroup;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
	public AtomicBoolean shutdownFlag() {
		return new AtomicBoolean(false);
	}

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
	public FactoryBean<Promise<Void>> gitterSlackRelay() {
		return new FactoryBean<Promise<Void>>() {
			@Override
			public Promise<Void> getObject() throws Exception {
				return httpClient()
						.get("https://stream.gitter.im/v1/rooms/" + gitterRoomId + "/chatMessages", gitterHandler())
						.flatMap(ch -> ch
								.filter(b -> b.remaining() > 2)
								.decode(new JsonCodec<>(Map.class))
								.window(10, 1, TimeUnit.SECONDS)
								.flatMap(msg -> httpClient(spec -> spec.options(clientSocketOptions()))
										.post(slackWebhookUrl, out -> {
											out.header(Headers.CONTENT_TYPE, "application/json");
											return out.writeWith(msg.map(m -> formatLink(m) +
											                                  ": " +
											                                  replaceNewlines(read(m, "$.text")))
											                        .reduce("", (prev, next) -> (!"".equals(prev) ? prev + "\\\\n" + next : next))
											                        .map(s -> Buffer.wrap("{\"text\": \"" + s + "\"}")));
										})
										.flatMap(Stream::after)));
			}

			@Override
			public Class<?> getObjectType() {
				return Promise.class;
			}

			@Override
			public boolean isSingleton() {
				return false;
			}
		};
	}

	public static void main(String[] args) throws InterruptedException {
		ApplicationContext ctx = SpringApplication.run(GitterSlackRelayApplication.class, args);

		AtomicBoolean shutdownFlag = ctx.getBean(AtomicBoolean.class);
		while (!shutdownFlag.get()) {
			ctx.getBean(Promise.class).await(-1, TimeUnit.SECONDS);
		}
	}

	private static String replaceNewlines(Object o) {
		return ((String) o).replaceAll("\n", "\\\\n");
	}

	private static String formatLink(Map m) {
		return "<https://gitter.im/reactor/reactor?at=" +
		       read(m, "$.id") + "|" + read(m, "$.fromUser.displayName") + " [" +
		       formatDate(read(m, "$.sent")) + "]>";
	}

	private static String formatDate(Object o) {
		DateTimeFormatter isoDateFmt = ISODateTimeFormat.dateTime();
		DateTimeFormatter shortFmt = DateTimeFormat.forPattern("d-MMM H:mm:ss");
		DateTime dte = isoDateFmt.parseDateTime((String) o);
		return shortFmt.print(dte);
	}

}
