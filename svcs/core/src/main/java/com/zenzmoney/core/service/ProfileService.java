package com.zenzmoney.core.service;

import com.zenzmoney.core.entity.User;
import com.zenzmoney.core.repository.UserRepository;
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

    public ProfileService(UserRepository userRepository, CurrentUserService currentUser) {
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public MeResponse updateProfile(UpdateProfileRequest req) {
        User user = currentUser.requireUser();

        if (req.getFirstName() != null && !req.getFirstName().isBlank()) {
            user.setFirstName(req.getFirstName().trim());
        }
        if (req.getLastName() != null && !req.getLastName().isBlank()) {
            user.setLastName(req.getLastName().trim());
        }
        userRepository.save(user);

        log.info("Profile updated for user {}", user.getId());
        return MeResponse.of(user);
    }
}
