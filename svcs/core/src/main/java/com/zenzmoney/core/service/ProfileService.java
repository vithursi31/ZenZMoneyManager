package com.zenzmoney.core.service;

import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
import com.zenzmoney.core.util.SupportedLanguages;
import com.zenzmoney.core.web.dto.MeResponse;
import com.zenzmoney.core.web.dto.UpdateProfileRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository userRepository;
    private final CurrentUserService currentUser;
    private final SupportedLanguages supportedLanguages;

    public ProfileService(UserRepository userRepository, CurrentUserService currentUser,
                          SupportedLanguages supportedLanguages) {
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.supportedLanguages = supportedLanguages;
    }

    @Transactional
    public MeResponse updateProfile(UpdateProfileRequest req) {
        User user = currentUser.requireUser();
        String previousLanguage = user.getLanguage();

        if (req.getFirstName() != null && !req.getFirstName().isBlank()) {
            user.setFirstName(req.getFirstName().trim());
        }
        if (req.getLastName() != null && !req.getLastName().isBlank()) {
            user.setLastName(req.getLastName().trim());
        }
        if (req.getLanguage() != null && !req.getLanguage().isBlank()) {
            user.setLanguage(supportedLanguages.normaliseOrThrow(req.getLanguage()));
        }
        userRepository.save(user);

        log.info("Profile updated for user {} (language {} -> {})",
                user.getId(), previousLanguage, user.getLanguage());
        return MeResponse.of(user);
    }
}
