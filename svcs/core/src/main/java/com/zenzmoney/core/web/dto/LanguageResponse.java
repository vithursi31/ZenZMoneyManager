package com.zenzmoney.core.web.dto;

import lombok.Getter;

/**
 * One selectable language (F-1.26/F-1.27). Carries {@code nativeName} because a language picker is
 * the one screen a user reads <em>before</em> the app speaks their language — "Deutsch" is findable
 * to someone who cannot read "German".
 */
@Getter
public class LanguageResponse {

    private final String tag;
    private final String name;
    private final String nativeName;

    public LanguageResponse(String tag, String name, String nativeName) {
        this.tag = tag;
        this.name = name;
        this.nativeName = nativeName;
    }
}
