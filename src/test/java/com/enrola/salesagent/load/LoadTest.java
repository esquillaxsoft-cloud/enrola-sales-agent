package com.enrola.salesagent.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.enrola.salesagent.agent.AgentOutput;
import com.enrola.salesagent.agent.AgentOutput.Stage;
import com.enrola.salesagent.agent.AgentReplier;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.lead.LeadClient;
import com.enrola.salesagent.lead.LeadRecord;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Token-free load test of Comparato's volume (750 leads a week), over real HTTP against the full
 * app. The model is replaced by a fake that waits like a real call (1-2 s) but costs nothing, and
 * the lead API by one that invents leads.
 *
 * <ol>
 * <li><b>A week's ingestion at once:</b> 750 leads started, 50 requests in flight at a time.</li>
 * <li><b>A busy day's conversations:</b> 150 leads (750 over five weekdays), all talking at the
 * same time, each sending 5-10 SMS and replying the moment the agent answers. Real leads reply
 * minutes or days apart, so this is far harsher than real traffic.</li>
 * </ol>
 *
 * Run with {@code ./mvnw test -Pload}. Writes {@code target/load-report.md}.
 */
@Tag("load")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"logging.level.root=WARN",
		"logging.level.com.enrola=WARN" })
@ActiveProfiles("test")
@Import(LoadTest.Fakes.class)
class LoadTest {

	static final int WEEK_OF_LEADS = 750;
	static final int INGEST_CONCURRENCY = 50;
	static final int LEADS_PER_DAY = 150;
	static final int MIN_MESSAGES = 5;
	static final int MAX_MESSAGES = 10;
	static final Duration MODEL_MIN = Duration.ofMillis(1000);
	static final Duration MODEL_MAX = Duration.ofMillis(2000);

	/** Neutral replies: none is an opt-out keyword or a phrase the stub would treat as an objection. */
	static final List<String> LEAD_MESSAGES = List.of("All of the above", "Mostly hospital", "Me and my partner",
			"Silver at the moment", "Maybe next week", "What does the advisor do?", "Is it really free?",
			"Mornings are better", "Ok sounds good", "Tuesday works");

	@TestConfiguration
	static class Fakes {

		/** Waits like a model call, then replies; spends no tokens. */
		@Bean
		@Primary
		AgentReplier slowFakeModel() {
			return conversation -> {
				long ms = ThreadLocalRandom.current().nextLong(MODEL_MIN.toMillis(), MODEL_MAX.toMillis() + 1);
				try {
					Thread.sleep(ms);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return AgentOutput.reply("Got it. Would tomorrow morning suit for a quick 15 minute call?",
						Stage.SUGGEST_CALL, "load test");
			};
		}

		/** Any id "warm-N", "load-N" or "day-N" is a valid lead with its own mobile number. */
		@Bean
		@Primary
		LeadClient syntheticLeads() {
			return id -> {
				int n = Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
				// Separate number ranges per scenario, so no lead is mistaken for a duplicate.
				int range = switch (id.substring(0, id.indexOf('-'))) {
					case "warm" -> 30_000_000;
					case "day" -> 20_000_000;
					default -> 10_000_000;
				};
				int number = range + n;
				return Optional.of(new LeadRecord(id, "Lead" + n, String.format("+614%08d", number),
						"lead" + n + "@example.com", "NSW", n % 3 == 0 ? "none" : "Bupa", "$300-$400"));
			};
		}

	}

	@LocalServerPort
	int port;

	@Autowired
	ConversationRepository conversations;

	final HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
	final JsonMapper json = JsonMapper.builder().build();
	final List<String> report = Collections.synchronizedList(new ArrayList<>());

	@Test
	void comparatosVolumeWithoutSpendingATokens() throws Exception {
		report.add("# Load test report\n");
		report.add("Fake model latency %d-%d ms per reply. Real HTTP, full app, in-memory H2.\n"
				.formatted(MODEL_MIN.toMillis(), MODEL_MAX.toMillis()));

		Stats warmUp = warmUp();
		Stats ingest = ingestAWeekAtOnce();
		Stats turns = runADaysConversations();

		Files.write(Path.of("target", "load-report.md"), report);
		report.forEach(System.out::println);

		assertThat(ingest.errors()).as("ingestion errors").isZero();
		assertThat(turns.errors()).as("turn errors").isZero();
		// The model call runs outside any transaction, so turns shouldn't queue behind each other:
		// a turn should take about one model call, not many.
		assertThat(turns.p(95)).as("p95 turn latency (ms)").isLessThan(MODEL_MAX.toMillis() + 1_000);
	}

	/**
	 * The first requests after start-up pay for class loading, JIT and opening database
	 * connections. Measured separately so the scenarios show steady-state behaviour.
	 */
	Stats warmUp() throws Exception {
		Stats stats = new Stats("Warm-up: first requests after start-up");
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 1; i <= INGEST_CONCURRENCY; i++) {
				String id = "warm-" + i;
				pool.submit(() -> stats.time(() -> post("/api/leads/" + id + "/conversation", null), 201));
			}
		}
		report.add("## 0. Cold start: the first %d requests at once (not part of the scenarios)\n"
				.formatted(INGEST_CONCURRENCY));
		report.add(stats.table(Duration.ZERO).lines().filter(l -> !l.contains("Wall time") && !l.contains("Throughput"))
				.reduce("", (a, b) -> a + b + "\n"));
		return stats;
	}

	/** Scenario 1: all 750 of a week's leads arrive at once. */
	Stats ingestAWeekAtOnce() throws Exception {
		Stats stats = new Stats("Start conversation (POST /api/leads/{id}/conversation)");
		Semaphore inFlight = new Semaphore(INGEST_CONCURRENCY);
		long start = System.nanoTime();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 1; i <= WEEK_OF_LEADS; i++) {
				String id = "load-" + i;
				inFlight.acquire();
				pool.submit(() -> {
					try {
						stats.time(() -> post("/api/leads/" + id + "/conversation", null), 201);
					}
					finally {
						inFlight.release();
					}
				});
			}
		}
		Duration wall = Duration.ofNanos(System.nanoTime() - start);

		report.add("## 1. A week's leads (%d) arriving at once, %d in flight\n".formatted(WEEK_OF_LEADS,
				INGEST_CONCURRENCY));
		report.add(stats.table(wall));
		report.add("Conversations in the database (incl. warm-up): %d\n".formatted(conversations.count()));
		return stats;
	}

	/** Scenario 2: a day's leads all mid-conversation at the same moment. */
	Stats runADaysConversations() throws Exception {
		Stats stats = new Stats("Inbound SMS turn (POST /api/conversations/{id}/inbound)");
		ConcurrentHashMap<Long, Integer> sentPerConversation = new ConcurrentHashMap<>();
		AtomicInteger totalSms = new AtomicInteger();
		long start = System.nanoTime();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 1; i <= LEADS_PER_DAY; i++) {
				String leadId = "day-" + i;
				pool.submit(() -> {
					JsonNode started = post("/api/leads/" + leadId + "/conversation", null).body();
					long id = started.path("conversationId").asLong();
					int count = ThreadLocalRandom.current().nextInt(MIN_MESSAGES, MAX_MESSAGES + 1);
					for (int m = 0; m < count; m++) {
						String body = LEAD_MESSAGES.get(m % LEAD_MESSAGES.size());
						String request = "{\"messageId\":\"%s-%d\",\"body\":\"%s\"}".formatted(leadId, m, body);
						stats.time(() -> post("/api/conversations/" + id + "/inbound", request), 200);
						totalSms.incrementAndGet();
					}
					sentPerConversation.put(id, count);
					return null;
				});
			}
		}
		Duration wall = Duration.ofNanos(System.nanoTime() - start);

		// Every lead SMS got exactly one reply: opener + (lead + agent) per message.
		long wrong = sentPerConversation.entrySet().stream()
				.filter(e -> conversations.findWithMessagesById(e.getKey()).orElseThrow().getMessages().size()
						!= 1 + 2 * e.getValue())
				.count();

		report.add("## 2. A day's leads (%d) all talking at once, %d-%d SMS each, replying instantly\n"
				.formatted(LEADS_PER_DAY, MIN_MESSAGES, MAX_MESSAGES));
		report.add(stats.table(wall));
		report.add("Lead SMS handled: %d. Conversations with a wrong message count: %d\n".formatted(totalSms.get(), wrong));
		assertThat(wrong).as("conversations missing or duplicating a reply").isZero();
		return stats;
	}

	record Response(int status, JsonNode body) {
	}

	Response post(String path, String body) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(60));
		request = body == null
				? request.POST(HttpRequest.BodyPublishers.noBody())
				: request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		return new Response(response.statusCode(), json.readTree(response.body()));
	}

	/** Latency samples and failures for one kind of request. */
	static final class Stats {

		private final String name;
		private final ConcurrentLinkedQueue<Long> millis = new ConcurrentLinkedQueue<>();
		private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

		Stats(String name) {
			this.name = name;
		}

		interface Call {
			Response call() throws Exception;
		}

		void time(Call call, int expectedStatus) {
			long start = System.nanoTime();
			try {
				Response response = call.call();
				if (response.status() != expectedStatus) {
					failures.add(response.status() + " " + response.body());
				}
			}
			catch (Exception e) {
				failures.add(e.toString());
			}
			millis.add((System.nanoTime() - start) / 1_000_000);
		}

		int errors() {
			return failures.size();
		}

		long p(int percentile) {
			List<Long> sorted = millis.stream().sorted().toList();
			return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, sorted.size() * percentile / 100));
		}

		String table(Duration wall) {
			int n = millis.size();
			double perSecond = n / Math.max(0.001, wall.toMillis() / 1000.0);
			String sample = failures.stream().limit(3).reduce("", (a, b) -> a + "\n  - " + b);
			return """
					| %s | |
					|---|---|
					| Requests | %d |
					| Errors | %d%s |
					| Wall time | %.1f s |
					| Throughput | %.1f req/s |
					| p50 / p95 / p99 / max | %d / %d / %d / %d ms |
					""".formatted(name, n, errors(), sample, wall.toMillis() / 1000.0, perSecond, p(50), p(95), p(99),
					millis.stream().mapToLong(Long::longValue).max().orElse(0));
		}

	}

}
