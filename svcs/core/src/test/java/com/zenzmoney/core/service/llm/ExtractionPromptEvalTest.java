package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.domain.TransactionType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline prompt eval from the chat entry plan §10 — a fixture set of phrasings
 * run against a real model, across the languages the app supports (F-1.26) and the
 * terse styles people actually type.
 *
 * <p><b>Opt-in, and not part of the normal suite.</b> It needs a running Ollama and
 * takes seconds per fixture:
 * <pre>
 *   mvn test -pl svcs/core -am -Dtest=ExtractionPromptEvalTest -Dllm.eval=true \
 *            -Dsurefire.failIfNoSpecifiedTests=false
 *   # compare models: -Dllm.eval.model=qwen2.5:7b-instruct
 * </pre>
 *
 * <p><b>What it asserts vs what it reports.</b> The assertions are the parts of the
 * prompt that are a <em>contract</em> the backend depends on — an amount it can parse,
 * a category from the closed set, a date phrase and never an absolute date. Those must
 * hold for every fixture in every language. Per-field <em>accuracy</em> is printed, not
 * asserted: a small model varies between identical runs (this is how the txnType
 * instability and the Spanish "1.500" → "1.5" collapse were found), so a threshold here
 * would flake instead of informing. Read the table when tuning the prompt or changing
 * model.
 */
class ExtractionPromptEvalTest {

    private static final String BASE_URL = System.getProperty("llm.eval.base-url", "http://localhost:11434");
    private static final String MODEL = System.getProperty("llm.eval.model", "qwen2.5:1.5b-instruct");

    /** The closed set the prompt hands the model; a guess outside it is a contract break. */
    private static final List<String> CATEGORIES = List.of(
            "Business", "Education", "Entertainment", "Food & Drinks", "Freelance", "Gifts",
            "Groceries", "Health", "Housing", "Investments", "Other", "Salary", "Shopping",
            "Subscriptions", "Transport", "Utilities");

    /**
     * One phrasing and what the backend needs from it. {@code amount} is the number as
     * typed — the fidelity check is "did the model come back with the user's number",
     * which is the failure that silently writes wrong money.
     */
    private record Fixture(String lang, String message, TransactionType type, String amount) {}

    private static final List<Fixture> FIXTURES = List.of(
            // English — full sentences
            new Fixture("en", "I have spent $5 for burger", TransactionType.EXPENSE, "5"),
            new Fixture("en", "spent 1500 on lunch", TransactionType.EXPENSE, "1500"),
            new Fixture("en", "paid 250 for uber yesterday", TransactionType.EXPENSE, "250"),
            new Fixture("en", "bought medicine for 800 at pharmacy", TransactionType.EXPENSE, "800"),
            new Fixture("en", "got salary 3000 today", TransactionType.INCOME, "3000"),
            new Fixture("en", "received 450 freelance payment", TransactionType.INCOME, "450"),
            new Fixture("en", "spent 12.50 on tea", TransactionType.EXPENSE, "12.50"),
            new Fixture("en", "I spent $15 in the Keells supermarket for grocery", TransactionType.EXPENSE, "15"),

            // English — the terse styles people actually type
            new Fixture("style", "coffee 500", TransactionType.EXPENSE, "500"),
            new Fixture("style", "uber 250", TransactionType.EXPENSE, "250"),
            new Fixture("style", "lunch 1500", TransactionType.EXPENSE, "1500"),
            new Fixture("style", "groceries 3450", TransactionType.EXPENSE, "3450"),
            new Fixture("style", "500 lunch", TransactionType.EXPENSE, "500"),
            new Fixture("style", "rent 45000", TransactionType.EXPENSE, "45000"),
            new Fixture("style", "spent like 500 on lunch", TransactionType.EXPENSE, "500"),
            new Fixture("style", "Rs 500 for lunch at keells", TransactionType.EXPENSE, "500"),

            // French
            new Fixture("fr", "j'ai dépensé 15 pour le déjeuner", TransactionType.EXPENSE, "15"),
            new Fixture("fr", "j'ai payé 250 pour uber hier", TransactionType.EXPENSE, "250"),
            new Fixture("fr", "j'ai reçu mon salaire de 3000", TransactionType.INCOME, "3000"),
            new Fixture("fr", "acheté des médicaments pour 800 à la pharmacie", TransactionType.EXPENSE, "800"),
            new Fixture("fr", "café 500", TransactionType.EXPENSE, "500"),
            new Fixture("fr", "15,50 pour le thé", TransactionType.EXPENSE, "15.50"),

            // Spanish
            new Fixture("es", "gasté 15 en el almuerzo", TransactionType.EXPENSE, "15"),
            new Fixture("es", "pagué 250 por uber ayer", TransactionType.EXPENSE, "250"),
            new Fixture("es", "recibí mi salario de 3000", TransactionType.INCOME, "3000"),
            new Fixture("es", "compré medicina por 800 en la farmacia", TransactionType.EXPENSE, "800"),
            new Fixture("es", "café 500", TransactionType.EXPENSE, "500"),
            new Fixture("es", "gasté 1500 en el supermercado", TransactionType.EXPENSE, "1500"),

            // Portuguese
            new Fixture("pt", "gastei 15 no almoço", TransactionType.EXPENSE, "15"),
            new Fixture("pt", "paguei 250 no táxi ontem", TransactionType.EXPENSE, "250"),
            new Fixture("pt", "recebi o meu salário de 3000", TransactionType.INCOME, "3000"),
            new Fixture("pt", "café 500", TransactionType.EXPENSE, "500"),

            // German
            new Fixture("de", "habe 15 für das Mittagessen ausgegeben", TransactionType.EXPENSE, "15"),
            new Fixture("de", "gestern 250 für ein Taxi bezahlt", TransactionType.EXPENSE, "250"),
            new Fixture("de", "Gehalt 3000 erhalten", TransactionType.INCOME, "3000"),
            new Fixture("de", "Kaffee 500", TransactionType.EXPENSE, "500"),

            // Italian
            new Fixture("it", "ho speso 15 per il pranzo", TransactionType.EXPENSE, "15"),
            new Fixture("it", "ho pagato 250 per il taxi ieri", TransactionType.EXPENSE, "250"),
            new Fixture("it", "ho ricevuto lo stipendio di 3000", TransactionType.INCOME, "3000"),
            new Fixture("it", "caffè 500", TransactionType.EXPENSE, "500"),

            // Russian
            new Fixture("ru", "потратил 15 на обед", TransactionType.EXPENSE, "15"),
            new Fixture("ru", "заплатил 250 за такси вчера", TransactionType.EXPENSE, "250"),
            new Fixture("ru", "получил зарплату 3000", TransactionType.INCOME, "3000"),
            new Fixture("ru", "кофе 500", TransactionType.EXPENSE, "500"),

            // Japanese
            new Fixture("ja", "昼食に15使った", TransactionType.EXPENSE, "15"),
            new Fixture("ja", "昨日タクシーで250払った", TransactionType.EXPENSE, "250"),
            new Fixture("ja", "給料3000を受け取った", TransactionType.INCOME, "3000"),
            new Fixture("ja", "コーヒー 500", TransactionType.EXPENSE, "500"),

            // Korean
            new Fixture("ko", "점심에 15 썼어", TransactionType.EXPENSE, "15"),
            new Fixture("ko", "어제 택시비 250 냈어", TransactionType.EXPENSE, "250"),
            new Fixture("ko", "월급 3000 받았어", TransactionType.INCOME, "3000"),
            new Fixture("ko", "커피 500", TransactionType.EXPENSE, "500"),

            // Chinese — the SAME six sentences in both scripts, converted character for character
            // and nothing else. Regional wording (計程車 vs 打车) is deliberately held constant so
            // this row pair measures script alone: if Simplified and Traditional score differently
            // here, it is the writing system and not the vocabulary.
            new Fixture("zh-Hans", "我花了15块买午餐", TransactionType.EXPENSE, "15"),
            new Fixture("zh-Hans", "昨天打车花了250元", TransactionType.EXPENSE, "250"),
            new Fixture("zh-Hans", "我收到工资3000", TransactionType.INCOME, "3000"),
            new Fixture("zh-Hans", "在药店买药花了800", TransactionType.EXPENSE, "800"),
            new Fixture("zh-Hans", "在超市买菜花了1200", TransactionType.EXPENSE, "1200"),
            new Fixture("zh-Hans", "咖啡 500", TransactionType.EXPENSE, "500"),

            new Fixture("zh-Hant", "我花了15塊買午餐", TransactionType.EXPENSE, "15"),
            new Fixture("zh-Hant", "昨天打車花了250元", TransactionType.EXPENSE, "250"),
            new Fixture("zh-Hant", "我收到工資3000", TransactionType.INCOME, "3000"),
            new Fixture("zh-Hant", "在藥店買藥花了800", TransactionType.EXPENSE, "800"),
            new Fixture("zh-Hant", "在超市買菜花了1200", TransactionType.EXPENSE, "1200"),
            new Fixture("zh-Hant", "咖啡 500", TransactionType.EXPENSE, "500"));

    @Test
    void everyPhrasingHonoursTheExtractionContract() {
        Assumptions.assumeTrue(Boolean.getBoolean("llm.eval"),
                "prompt eval is opt-in: pass -Dllm.eval=true with a model running");
        Assumptions.assumeTrue(reachable(), () -> "no model reachable at " + BASE_URL);

        OllamaExtractionClient client = client();
        List<String> breaches = new ArrayList<>();
        Map<String, int[]> score = new LinkedHashMap<>();   // lang -> {type ok, amount ok, total}

        System.out.printf("%nprompt eval — model=%s%n%n", MODEL);
        System.out.printf("%-6s %-48s %-8s %-9s %s%n", "lang", "message", "type", "amount", "category");

        for (Fixture f : FIXTURES) {
            LlmExtraction got = client.extract(f.message(), CATEGORIES, null);
            int[] tally = score.computeIfAbsent(f.lang(), k -> new int[3]);
            tally[2]++;

            // --- contract: must hold whatever the model thinks the message means ---
            if (got.isFailed()) {
                breaches.add(f.message() + " — no parseable extraction");
                System.out.printf("%-6s %-48s %s%n", f.lang(), f.message(), "FAILED");
                continue;
            }
            if (got.getAmountRaw() != null
                    && IntentResolver.toMinorUnits(got.getAmountRaw(), "USD") == null) {
                breaches.add(f.message() + " — amount \"" + got.getAmountRaw() + "\" is not a plain decimal");
            }
            if (got.getCategoryGuess() != null && !CATEGORIES.contains(got.getCategoryGuess())) {
                breaches.add(f.message() + " — category \"" + got.getCategoryGuess() + "\" is outside the list");
            }
            if (got.getDateExpr() != null && got.getDateExpr().matches(".*\\d.*")) {
                breaches.add(f.message() + " — dateExpr \"" + got.getDateExpr() + "\" looks computed, not a phrase");
            }

            // --- accuracy: reported, so prompt and model changes can be compared ---
            boolean typeOk = got.getTxnType() == f.type();
            // Compared as money, not as text: "250.00" and "250" are the same amount,
            // while "5.00" against a typed 500 is the failure worth seeing.
            boolean amountOk = IntentResolver.toMinorUnits(f.amount(), "USD")
                    .equals(IntentResolver.toMinorUnits(got.getAmountRaw(), "USD"));
            if (typeOk) tally[0]++;
            if (amountOk) tally[1]++;

            System.out.printf("%-6s %-48s %-8s %-9s %s%n",
                    f.lang(), f.message(),
                    (typeOk ? "ok " : "BAD ") + abbreviate(got.getTxnType()),
                    (amountOk ? "ok " : "BAD ") + got.getAmountRaw(),
                    got.getCategoryGuess());
        }

        System.out.printf("%n%-6s %-12s %s%n", "lang", "type", "amount");
        score.forEach((lang, t) -> System.out.printf("%-6s %-12s %s%n",
                lang, t[0] + "/" + t[2], t[1] + "/" + t[2]));
        System.out.println();

        assertTrue(breaches.isEmpty(), () -> "extraction contract broken:\n  " + String.join("\n  ", breaches));
    }

    private static String abbreviate(TransactionType type) {
        return type == null ? "null" : type.name().substring(0, 3).toLowerCase(Locale.ROOT);
    }

    private OllamaExtractionClient client() {
        WebClient webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create()
                                .responseTimeout(Duration.ofSeconds(60))))
                .build();
        return new OllamaExtractionClient(webClient, new ObjectMapper(),
                new ExtractionPrompt(new ClassPathResource("prompts/extraction-system.md")),
                MODEL, 0.1d);
    }

    /** Cheap liveness probe so a missing model skips the eval instead of failing it. */
    private static boolean reachable() {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(BASE_URL + "/api/tags").toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
