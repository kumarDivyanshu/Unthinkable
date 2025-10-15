package com.Unthinkable.Summarizer.service;

import com.Unthinkable.Summarizer.model.User;
import com.Unthinkable.Summarizer.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public User requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("No authenticated user");
        }
        String email = auth.getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }

    public User requireCurrentUserOrGuest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = (auth != null ? auth.getName() : null);
        if (email == null || email.equalsIgnoreCase("anonymousUser")) {
            // Provide or create a guest user for unauthenticated uploads
            return userRepository.findByEmail("guest@local").orElseGet(() -> {
                User guest = new User();
                guest.setEmail("guest@local");
                guest.setFullName("Guest");
                guest.setPasswordHash("noop");
                return userRepository.save(guest);
            });
        }
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }
}
