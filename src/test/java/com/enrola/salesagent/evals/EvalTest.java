package com.enrola.salesagent.evals;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.enrola.salesagent.agent.AgentTurn;
import com.enrola.salesagent.agent.AgentTurn.Usage;
import com.enrola.salesagent.agent.AgentUnavailableException;
import com.enrola.salesagent.agent.BookingTools;
import com.enrola.salesagent.agent.PromptBuilder;
import com.enrola.salesagent.agent.openai.OpenAiAgentReplier;
import com.enrola.salesagent.agent.openai.OpenAiProperties;
import com.enrola.salesagent.config.AgentProperties;
import com.enrola.salesagent.conversation.Conversation;
import com.enrola.salesagent.conversation.ConversationRepository;
import com.enrola.salesagent.conversation.Message;
import com.enrola.salesagent.conversation.Opener;
import com.enrola.salesagent.evals.Checks.Check;
import com.enrola.salesagent.lead.AuState;
import com.enrola.salesagent.lead.Lead;
import com.enrola.salesagent.lead.LeadRecord;
import com.enrola.salesagent.lead.LeadRepository;
import com.enrola.salesagent.lead.PhoneNumbers;
import com.enrola.salesagent.lead.SampleLeads;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quality against cost, per OpenAI model, for the current prompt bundle (DESIGN.md §8).
 *
 * <p>Each case is one model call through the production client ({@link OpenAiAgentReplier}, the
 * real {@link PromptBuilder} and tools), judged by {@link Checks}. Outputs are cached by model +
 * bundle hash + case input, so only what changed is paid for; a run stops spending at the budget.
 * Writes {@code target/eval-report.md}. It measures; it doesn't pass or fail on quality.
 *
 * <pre>
 * ./mvnw test -Peval
 * ./mvnw test -Peval -Deval.models=gpt-6.1-sol,gpt-6-luna -Deval.budget=0.25
 * </pre>
 */
@Tag("eval")
@SpringBootTest(properties = "openai.api-key=${OPENAI_API_KEY:}")
@ActiveProfiles("test")
class EvalTest {

	static final String DEFAULT_MODELS = "gpt-6.1-sol,gpt-6-luna,gpt-6-astra";
	static final double DEFAULT_BUDGET_USD = 1.00;
	static final Path CACHE = Path.of(".eval-cache");

	@Autowired
	OpenAiProperties openAi;

	@Autowired
	AgentProperties agent;

	@Autowired
	PromptBuilder prompts;

	@Autowired
	BookingTools tools;

	@Autowired
	Opener opener;

	@Autowired
	SampleLeads samples;

	@Autowired
	LeadRepository leads;

	@Autowired
	ConversationRepository conversations;

	@Autowired
	Clock clock;

	final JsonMapper json = JsonMapper.builder().build();

	/** One case run against one model. */
	record Result(String model, EvalCase evalCase, AgentTurn turn, List<Check> checks, String source, String error) {

		boolean passed() {
			return error == null && turn != null && checks.stream().allMatch(Check::passed);
		}

	}

	@Test
	void qualityAgainstCostPerModel() throws IOException {
		assumeTrue(openAi.hasKey(), "No OPENAI_API_KEY: evals skipped");
		List<String> models = Arrays.stream(System.getProperty("eval.models", DEFAULT_MODELS).split(","))
				.map(String::strip).filter(m -> !m.isEmpty()).toList();
		double budget = Double.parseDouble(System.getProperty("eval.budget", String.valueOf(DEFAULT_BUDGET_USD)));

		List<EvalCase> cases = loadCases();
		JsonNode prices = json.readTree(new PathMatchingResourcePatternResolver()
				.getResource("classpath:evals/prices.json").getContentAsString(StandardCharsets.UTF_8));
		String bundle = bundleHash();
		EvalCache cache = new EvalCache(CACHE, json);

		double spent = 0;
		List<Result> results = new ArrayList<>();
		for (String model : models) {
			OpenAiAgentReplier replier = replierFor(model);
			for (EvalCase evalCase : cases) {
				String key = EvalCache.key(model, bundle, evalCase);
				Optional<AgentTurn> cached = cache.get(key);
				if (cached.isPresent()) {
					results.add(judged(model, evalCase, cached.get(), "cached"));
					continue;
				}
				if (spent >= budget) {
					results.add(new Result(model, evalCase, null, List.of(), "skipped", "budget reached"));
					continue;
				}
				try {
					AgentTurn turn = replier.reply(conversationFor(evalCase));
					cache.put(key, turn);
					spent += cost(turn.usage(), prices.path(model));
					results.add(judged(model, evalCase, turn, "live"));
				}
				catch (AgentUnavailableException | IllegalStateException e) {
					results.add(new Result(model, evalCase, null, List.of(), "live", e.getMessage()));
				}
			}
		}

		List<String> report = report(models, cases, results, prices, bundle, spent, budget);
		Files.createDirectories(Path.of("target"));
		Files.write(Path.of("target", "eval-report.md"), report);
		// -Deval.record=true keeps the report in source, so results can be compared across versions.
		if (Boolean.getBoolean("eval.record")) {
			Path recorded = Path.of("evals", "results",
					"%s-%s-%s.md".formatted(clock.instant().toString().substring(0, 10), agent.version().replace('/', '-'), bundle));
			Files.createDirectories(recorded.getParent());
			Files.write(recorded, report);
			System.out.println("Recorded: " + recorded);
		}
		report.forEach(System.out::println);
	}

	private Result judged(String model, EvalCase evalCase, AgentTurn turn, String source) {
		return new Result(model, evalCase, turn, Checks.judge(evalCase, turn.output()), source, null);
	}

	// --- building each case's conversation ---

	/** A fresh conversation in the database (the tools look it up), with the real opener first. */
	private Conversation conversationFor(EvalCase evalCase) {
		Instant now = clock.instant();
		LeadRecord sample = samples.find(evalCase.lead())
				.orElseThrow(() -> new IllegalArgumentException("Unknown lead " + evalCase.lead()));
		Lead lead = leads.save(new Lead("eval-" + evalCase.id() + "-" + UUID.randomUUID(), sample.givenName(),
				PhoneNumbers.toE164(sample.phone()), sample.email(), AuState.valueOf(sample.state()),
				"none".equalsIgnoreCase(sample.currentProvider()) ? null : sample.currentProvider(),
				sample.currentPremium(), now));

		Conversation conversation = new Conversation(lead, agent.version(), agent.defaultLocale(), now);
		conversation.append(opener.compose(conversation, now));
		if (evalCase.history() != null) {
			evalCase.history().forEach(turn -> conversation.append("agent".equals(turn.from())
					? Message.fromModel(turn.body(), now)
					: Message.fromLead(turn.body(), now)));
		}
		conversation.append(Message.fromLead(evalCase.message(), now));
		return conversations.save(conversation);
	}

	private OpenAiAgentReplier replierFor(String model) {
		var properties = new OpenAiProperties(openAi.apiKey(), model, openAi.baseUrl(), openAi.timeout(),
				openAi.maxOutputTokens(), openAi.moderationModel());
		var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
		requests.setReadTimeout(Duration.ofSeconds(60));
		return new OpenAiAgentReplier(RestClient.builder().requestFactory(requests), properties, prompts, tools, json);
	}

	private List<EvalCase> loadCases() throws IOException {
		List<EvalCase> cases = new ArrayList<>();
		for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:evals/cases/*.json")) {
			cases.add(json.readValue(resource.getContentAsString(StandardCharsets.UTF_8), EvalCase.class));
		}
		cases.sort(Comparator.comparing(EvalCase::id));
		return cases;
	}

	/**
	 * Everything that shapes the model's answer and lives outside the case: the prompt bundle's
	 * files, the output schema and the tool definitions. Change any of them and cases run again.
	 */
	private String bundleHash() throws IOException {
		StringBuilder all = new StringBuilder();
		Resource[] files = new PathMatchingResourcePatternResolver()
				.getResources("classpath:agents/" + agent.version() + "/**/*.*");
		Arrays.sort(files, Comparator.comparing(Resource::getDescription));
		for (Resource file : files) {
			all.append(file.getFilename()).append('\n')
					.append(file.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
		}
		all.append(new PathMatchingResourcePatternResolver().getResource("classpath:agent/agent-output.schema.json")
				.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
		// Sorted keys: the definitions are built with Map.of, whose iteration order changes from one
		// JVM start to the next, and the hash must not.
		all.append(JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
				.writeValueAsString(tools.definitions()));
		return EvalCache.sha256(all.toString()).substring(0, 12);
	}

	private static double cost(Usage usage, JsonNode price) {
		if (usage == null || price.isMissingNode()) {
			return 0;
		}
		long uncached = usage.inputTokens() - usage.cachedTokens();
		return (uncached * price.path("input").asDouble() + usage.cachedTokens() * price.path("cachedInput").asDouble()
				+ usage.outputTokens() * price.path("output").asDouble()) / 1_000_000;
	}

	// --- the report ---

	private List<String> report(List<String> models, List<EvalCase> cases, List<Result> results, JsonNode prices,
			String bundle, double spent, double budget) {
		List<String> out = new ArrayList<>();
		out.add("# Eval report: quality against cost\n");
		out.add(String.format(Locale.UK, "Agent bundle `%s` (hash `%s`), %d cases. Spent this run: **$%.4f** of a $%.2f budget.%n",
				agent.version(), bundle, cases.size(), spent, budget));
		out.add("Cost is at the prices in `evals/prices.json` (assumed; check OpenAI's). Cached results cost $0 to re-judge.\n");
		out.add("| Model | Cases passed | Checks passed | p50 latency | Avg tokens in (cached) / out | $ per case | $ per passing case | This run |");
		out.add("|---|---|---|---|---|---|---|---|");

		Map<String, List<Result>> byModel = new LinkedHashMap<>();
		models.forEach(model -> byModel.put(model, results.stream().filter(r -> r.model().equals(model)).toList()));
		byModel.forEach((model, rows) -> {
			List<Result> judged = rows.stream().filter(r -> r.turn() != null).toList();
			long passedCases = rows.stream().filter(Result::passed).count();
			long checks = judged.stream().mapToLong(r -> r.checks().size()).sum();
			long passedChecks = judged.stream().mapToLong(r -> r.checks().stream().filter(Check::passed).count()).sum();
			List<Long> latencies = judged.stream().filter(r -> r.turn().usage() != null)
					.map(r -> r.turn().usage().latencyMillis()).sorted().toList();
			double totalCost = judged.stream().mapToDouble(r -> cost(r.turn().usage(), prices.path(model))).sum();
			double n = Math.max(1, judged.size());
			String source = "%d live, %d cached%s".formatted(
					rows.stream().filter(r -> "live".equals(r.source()) && r.turn() != null).count(),
					rows.stream().filter(r -> "cached".equals(r.source())).count(),
					rows.stream().anyMatch(r -> r.error() != null)
							? ", %d not run".formatted(rows.stream().filter(r -> r.error() != null).count()) : "");
			out.add(String.format(Locale.UK, "| %s | %d / %d | %d / %d | %s | %,.0f (%,.0f) / %,.0f | $%.4f | %s | %s |",
					model, passedCases, rows.size(), passedChecks, checks,
					latencies.isEmpty() ? "-" : "%.1f s".formatted(latencies.get(latencies.size() / 2) / 1000.0),
					judged.stream().mapToLong(r -> usage(r).inputTokens()).sum() / n,
					judged.stream().mapToLong(r -> usage(r).cachedTokens()).sum() / n,
					judged.stream().mapToLong(r -> usage(r).outputTokens()).sum() / n,
					totalCost / n,
					passedCases == 0 ? "-" : "$%.4f".formatted(totalCost / passedCases),
					source));
		});

		out.add("\n## What failed\n");
		boolean any = false;
		for (Result result : results) {
			if (result.passed()) {
				continue;
			}
			any = true;
			out.add("- **%s / %s** (%s)".formatted(result.model(), result.evalCase().id(), result.evalCase().about()));
			if (result.error() != null) {
				out.add("  - not run: " + result.error());
				continue;
			}
			result.checks().stream().filter(c -> !c.passed()).forEach(c -> out.add("  - " + c.name() + ": " + c.detail()));
			out.add("  - reply: " + Optional.ofNullable(result.turn().output().reply()).orElse("(none)"));
		}
		if (!any) {
			out.add("Nothing: every case passed on every model.");
		}
		return out;
	}

	private static Usage usage(Result result) {
		return result.turn().usage() == null ? new Usage("", 0, 0, 0, 0, 0) : result.turn().usage();
	}

}
