package com.zenzmoney.core.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Confirms or rejects a draft, identified by the assistant turn that carries it. */
@Getter
@Setter
public class ChatActionRequest {

    @NotBlank
    private String messageId;
}
