package com.zenzmoney.core.i18n;

import com.zenzmoney.common.i18n.MessageKey;
import com.zenzmoney.common.i18n.Msg;
import com.zenzmoney.common.status.ServiceCodes;
import com.zenzmoney.common.status.StatusCode;
import com.zenzmoney.common.status.StatusCodes;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.validation.FieldError;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fallback ladder, which is what keeps a half-finished translation from ever being visible as
 * a defect: message key, then the code's own generic text, then the English default in the registry.
 */
class MessageResolverTest {

    private static final Locale SINHALA = Locale.forLanguageTag("si");
    /** Dutch: a language the app ships no bundle for, so it must fall through to the base. */
    private static final Locale UNTRANSLATED = Locale.forLanguageTag("nl");

    private final MessageResolver resolver = TestMessages.resolver();

    @Test
    void aKeyResolvesInTheRequestedLanguage() {
        String english = resolver.render(StatusCodes.SC_BAD_REQUEST.with(Msg.EMAIL_IN_USE), Locale.ENGLISH);
        String sinhala = resolver.render(StatusCodes.SC_BAD_REQUEST.with(Msg.EMAIL_IN_USE), SINHALA);

        assertEquals("Email already in use", english);
        assertNotEquals(english, sinhala);
    }

    @Test
    void aCodeWithNoKeyFallsBackToItsGenericMessage() {
        assertEquals("Session expired, please sign in again.",
                resolver.render(ServiceCodes.SC_TOKEN_EXPIRED, Locale.ENGLISH));
        assertNotEquals("Session expired, please sign in again.",
                resolver.render(ServiceCodes.SC_TOKEN_EXPIRED, SINHALA));
    }

    /** A diagnostic can name a library, a provider or a column. It is for the log, not the client. */
    @Test
    void aDiagnosticNeverReachesTheRenderedMessage() {
        StatusCode withDiagnostic =
                ServiceCodes.SC_TOKEN_INVALID.with("JWT signature does not match locally computed signature");

        String rendered = resolver.render(withDiagnostic, Locale.ENGLISH);

        assertEquals("Invalid token", rendered);
        assertFalse(rendered.contains("signature"));
    }

    /** A key nobody has added to any bundle degrades to English text, never to the key itself. */
    @Test
    void anUnknownKeyDegradesToTheCodesMessage_notToTheRawKey() {
        StatusCode orphan = StatusCodes.SC_BAD_REQUEST.with(MessageKey.of("error.nothing.here"));

        String rendered = resolver.render(orphan, Locale.ENGLISH);

        assertEquals("Bad request", rendered);
        assertFalse(rendered.contains("error.nothing.here"));
    }

    @Test
    void argumentsAreInterpolated() {
        assertEquals("Unknown currency code: XYZ",
                resolver.render(StatusCodes.SC_BAD_REQUEST.with(Msg.CURRENCY_UNKNOWN, "XYZ"), Locale.ENGLISH));
    }

    /** A numeric argument reaches the sentence as a plain number, in every language. */
    @Test
    void aNumericArgumentIsInterpolated() {
        assertEquals("Upcoming window must be between 1 and 90 days.",
                resolver.render(StatusCodes.SC_BAD_REQUEST.with(Msg.RECURRING_UPCOMING_WINDOW_INVALID, 90),
                        Locale.ENGLISH));
        assertTrue(resolver.render(StatusCodes.SC_BAD_REQUEST.with(Msg.RECURRING_UPCOMING_WINDOW_INVALID, 90),
                SINHALA).contains("90"));
    }

    /** A language with no bundle of its own must land on the base bundle, not the JVM default. */
    @Test
    void anUnsupportedLocaleFallsBackToEnglish() {
        assertEquals("Email already in use",
                resolver.render(StatusCodes.SC_BAD_REQUEST.with(Msg.EMAIL_IN_USE), UNTRANSLATED));
    }

    /**
     * Bean-validation reasons resolve through the codes Spring puts on a {@code FieldError},
     * down to the bare constraint name. **The argument order is Spring's, not ours** — the field
     * is {@code {0}} and the constraint's attributes follow it sorted by name, so for
     * {@code @Size} that is {@code {1}} = max and {@code {2}} = min. Pinned here because a
     * framework upgrade that reorders them would silently swap the two numbers in the message.
     */
    @Test
    void aFieldErrorResolvesThroughItsConstraintName_withTheArgumentsInSpringsOrder() {
        FieldError error = sizeViolation();

        assertEquals("length must be between 0 and 120", resolver.render(error, Locale.ENGLISH));
        assertNotEquals("length must be between 0 and 120", resolver.render(error, SINHALA));
    }

    /** An unmapped constraint still reads sensibly: the validator's own text is the default. */
    @Test
    void anUnmappedConstraintFallsBackToTheValidatorsOwnMessage() {
        FieldError error = new FieldError("createCategoryRequest", "name", null, false,
                new String[] {"NoSuchConstraint.createCategoryRequest.name", "NoSuchConstraint"},
                new Object[] {}, "must look like a category name");

        assertEquals("must look like a category name", resolver.render(error, SINHALA));
    }

    private static FieldError sizeViolation() {
        return new FieldError("updateProfileRequest", "firstName", "xxx", false,
                new String[] {
                        "Size.updateProfileRequest.firstName",
                        "Size.firstName",
                        "Size.java.lang.String",
                        "Size"},
                new Object[] {
                        new DefaultMessageSourceResolvable(
                                new String[] {"updateProfileRequest.firstName", "firstName"}, "firstName"),
                        120,
                        0},
                "size must be between 0 and 120");
    }
}
