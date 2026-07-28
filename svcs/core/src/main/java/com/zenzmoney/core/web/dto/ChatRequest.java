package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A typed capture message (F-1.9a). The length cap is a real control, not a
 * formality: the message goes into a model prompt, so an unbounded one is
 * unbounded compute.
 */
@Getter
@Setter
public class ChatRequest {

    @NotBlank
    @Size(max = 500)
    private String message;

    /** Continues an existing conversation; a new one is started when omitted. */
    @Size(max = 36)
    private String sessionId;
}
