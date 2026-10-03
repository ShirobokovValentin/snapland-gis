package com.snapland.gis.auth;

import com.snapland.gis.auth.dto.AuthResponse;
import com.snapland.gis.auth.dto.LoginRequest;
import com.snapland.gis.auth.dto.RegisterRequest;
import com.snapland.gis.common.exception.UserAlreadyExistsException;
import com.snapland.gis.common.exception.UserNotFoundException;
import com.snapland.gis.user.User;
import com.snapland.gis.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;

    public AuthService(UserRepository userRepository, JwtIssuer jwtIssuer) {
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String name = request.name().trim();

        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(email);
        }

        User user;
        try {
            user = userRepository.save(new User(name, email));
        } catch (DataIntegrityViolationException ex) {
            // concurrent registration with the same email
            throw new UserAlreadyExistsException(email);
        }

        String token = jwtIssuer.issue(user.getId());
        return toResponse(token, user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UserNotFoundException(email));
        String token = jwtIssuer.issue(user.getId());
        return toResponse(token, user);
    }

    private AuthResponse toResponse(String token, User user) {
        return new AuthResponse(token, new AuthResponse.UserInfo(user.getId(), user.getName(), user.getEmail()));
    }
}
